import groovy.json.JsonOutput

class MockDevice {
    String id
    Map settings
    Map data = [:]
    Map attributes = [switch: "off", level: 37]
    List removed = []
    String displayName = "Test dimmer"

    def currentValue(String name) { attributes[name] }
    String getDataValue(String name) { data[name] }
    void updateDataValue(String name, String value) { data[name] = value }
    void removeDataValue(String name) { data.remove(name) }
    void removeSetting(String name) { removed << name; settings.remove(name) }
    void updateSetting(String name, Object value) {
        settings[name] = value instanceof Map ? value.value : value
    }
}

class MockLog {
    void error(Object message) {}
    void warn(Object message) {}
    void info(Object message) {}
    void debug(Object message) {}
    void trace(Object message) {}
}

class MockZWave {
    Class commandClass
    List built = []

    def propertyMissing(String name) { this }
    def methodMissing(String name, Object arguments) {
        Map values = arguments ? arguments[0] as Map : [:]
        built << [name: name, values: values]
        commandClass.newInstance(values)
    }
}

assert args.length == 1: "Pass the complete driver source file"
String source = new File(args[0]).getText("UTF-8")
def loader = new GroovyClassLoader(getClass().classLoader)
Class commandClass = loader.parseClass('''
package hubitat.zwave
class Command {
    Map values
    Command(Map values = [:]) { this.values = values }
    def propertyMissing(String name) { values[name] }
    void propertyMissing(String name, Object value) { values[name] = value }
    def encapsulate(Command command) { values.encapsulated = command.values; this }
    String format() { "CMD:${values}" }
}
''')
source.findAll(/hubitat\.zwave\.commands\.[A-Za-z0-9_.]+/).unique().each { name ->
    String packageName = name.substring(0, name.lastIndexOf("."))
    String className = name.substring(name.lastIndexOf(".") + 1)
    loader.parseClass("""
package ${packageName}
class ${className} extends hubitat.zwave.Command {
    ${className}(Map values = [:]) { super(values) }
}
""")
}
loader.parseClass('''
package hubitat.device
enum Protocol { ZWAVE }
class HubAction { HubAction(Object command, Object protocol) {} }
class HubMultiAction { HubMultiAction(Object commands, Object protocol) {} }
''')
loader.parseClass('''
package hubitat.helper
class HexUtils {
    static int hexStringToInt(String value) { Integer.parseInt(value, 16) }
    static String integerToHexString(int value, int width) {
        Integer.toHexString(value).padLeft(width * 2, "0")
    }
}
''')
Class driverClass = loader.parseClass(source, "DriverUnderTest.groovy")
int fixtureId = 0
def fixture = {
    Map settings = [:]
    Map state = [group1Assoc: true, assocGroups: 3, endPoints: 0, resyncAll: false]
    List jobs = []
    List events = []
    List sent = []
    List http = []
    def device = new MockDevice(id: "test-${++fixtureId}", settings: settings)
    def zwave = new MockZWave(commandClass: commandClass)
    def driver = driverClass.newInstance()
    def handlerName = { Object handler ->
        handler instanceof org.codehaus.groovy.runtime.MethodClosure ? handler.method : handler.toString()
    }
    driver.binding = new Binding([
        device: device, state: state, settings: settings, zwave: zwave,
        log: new MockLog(), zwaveHubNodeId: 1,
        location: [timeZone: TimeZone.getTimeZone("UTC")],
        now: { -> 100000L },
        runIn: { Number delay, Object handler -> jobs << [delay: delay, handler: handlerName(handler)] },
        runInMillis: { Number delay, Object handler, Map options = [:] ->
            jobs << [delay: delay, handler: handlerName(handler), options: options]
        },
        unschedule: { Object handler -> jobs.removeAll { it.handler == handlerName(handler) } },
        sendEvent: { Map event -> events << event; device.attributes[event.name] = event.value },
        sendHubCommand: { Object command -> sent << command },
        delayBetween: { List commands, Number delay -> commands },
        zwaveSecureEncap: { Object command -> command.toString() },
        httpGet: { Map options, Closure callback -> http << options }
    ])
    driver.metaClass.propertyMissing = { String name ->
        if (driver.metaClass.respondsTo(driver, name)) return driver.&"$name"
        settings[name]
    }
    [driver: driver, device: device, settings: settings, state: state,
     jobs: jobs, events: events, sent: sent, http: http, zwave: zwave]
}

List cases = []
def test = { String name, Closure body -> cases << [name: name, body: body] }
def report = { String className, Map values ->
    loader.loadClass("hubitat.zwave.commands.${className}").newInstance(values)
}

test("incomplete parameter sync preserves saved preferences") {
    def f = fixture()
    f.device.data.parameters = JsonOutput.toJson([
        [num: 1, size: 1, format: 0, defaultVal: 2, title: "Fade time"],
        [num: 2, size: 1, format: 0, defaultVal: 10, title: "Minimum"]
    ])
    f.settings.configParam1 = 7
    f.settings.configParam2 = 25
    f.driver.syncFromDevice()
    f.driver.zwaveEvent(report("configurationv3.ConfigurationReport",
        [parameterNumber: 1, scaledConfigurationValue: 7]))
    assert f.settings.configParam1 == 7
    assert f.settings.configParam2 == 25
    assert f.device.removed.empty
    assert f.driver.getParamValue([num: 2, defaultVal: 10]) == 25
}

test("zero and normalized-zero level requests do not block on") {
    [0, 0.1G].each { level ->
        def f = fixture()
        f.driver.setLevel(level)
        assert f.driver.on() != null
    }
}

test("off clears a preceding nonzero level guard") {
    def f = fixture()
    f.driver.setLevel(20)
    assert f.driver.on() == null
    f.driver.off()
    assert f.driver.on() != null
}

test("instant off is opt-in and leaves on transitions unchanged") {
    [null, false, true].each { enabled ->
        def f = fixture()
        if (enabled != null) f.settings.instantOff = enabled
        f.driver.off()
        def off = f.zwave.built.findAll { it.name == "switchMultilevelSet" }.last()
        assert off.values.value == 0
        assert off.values.dimmingDuration == (enabled ? 0 : 255)
        f.driver.on()
        def on = f.zwave.built.findAll { it.name == "switchMultilevelSet" }.last()
        assert on.values.value == 255
        assert on.values.dimmingDuration == 255
    }
}

test("activity remains local and schedules no external check-in") {
    def f = fixture()
    f.state.lastCheckInTime = 1L
    f.driver.updateLastCheckIn()
    assert f.state.lastCheckInTime > 1L
    assert f.state.lastCheckInDate
    assert f.jobs.empty
    assert f.http.empty
    assert !driverClass.declaredMethods.any { it.name in ["doCheckIn", "scheduleCheckIn"] }
}

test("on-only reports preserve brightness and valid levels remain bounded") {
    def f = fixture()
    f.driver.sendSwitchEvents(255, "digital")
    assert f.device.attributes.switch == "on"
    assert f.device.attributes.level == 37
    assert !f.events.any { it.name == "level" }
    f.driver.sendSwitchEvents(99, "digital")
    assert f.device.attributes.level == 100
    f.driver.sendSwitchEvents(20, "digital")
    assert f.device.attributes.level == 20
    [100, 254].each { reserved ->
        f.driver.sendSwitchEvents(reserved, "digital")
        assert f.device.attributes.level == 20
    }
    f.driver.sendSwitchEvents(0, "digital")
    assert f.device.attributes.level == 20
    f.settings.levelOff = true
    f.driver.sendSwitchEvents(0, "digital")
    assert f.device.attributes.level == 0
}

test("binary reports accept missing/zero duration and exclude transitions") {
    [null, 0].each { duration ->
        def f = fixture()
        f.driver.zwaveEvent(report("switchbinaryv2.SwitchBinaryReport",
            [value: 255, duration: duration]))
        assert f.device.attributes.switch == "on"
        assert f.device.attributes.level == 37
        f.driver.zwaveEvent(report("switchbinaryv2.SwitchBinaryReport",
            [value: 0, duration: duration]))
        assert f.device.attributes.switch == "off"
    }
    def f = fixture()
    f.driver.zwaveEvent(report("switchbinaryv2.SwitchBinaryReport", [value: 255, duration: 2]))
    assert f.events.empty
}

test("multilevel missing/zero duration fix still works and excludes transitions") {
    [null, 0].each { duration ->
        def f = fixture()
        f.driver.zwaveEvent(report("switchmultilevelv4.SwitchMultilevelReport",
            [value: 20, duration: duration]))
        assert f.device.attributes.switch == "on"
        assert f.device.attributes.level == 20
    }
    def f = fixture()
    f.driver.zwaveEvent(report("switchmultilevelv4.SwitchMultilevelReport",
        [value: 20, duration: 2]))
    assert f.events.empty
}

test("pending association count does not build commands or schedule retries") {
    def f = fixture()
    f.state.group1Assoc = false
    f.settings.supervisedCmds = true
    f.settings.assocDNI2 = "02, 03"
    f.settings.assocDNI3 = ""
    f.state.assocNodes = ["2": [3, 2], "3": [4]]
    f.device.data.zwAssociations = JsonOutput.toJson(
        (1..3).collect { [num: it, maxNodes: 5] })
    assert f.driver.pendingChanges == 2
    assert f.jobs.empty
    assert f.sent.empty
    assert f.zwave.built.empty
    f.state.group1Assoc = true
    f.settings.assocDNI3 = "04"
    assert f.driver.pendingChanges == 0
    f.state.resyncAll = true
    assert f.driver.pendingChanges == 0
    assert f.zwave.built.empty
}

test("logging defaults retain the timeout and explicit indefinite stays indefinite") {
    def f = fixture()
    f.settings.logLevelTime = "30"
    f.driver.checkLogLevel()
    assert f.settings.logLevel == "3"
    assert f.jobs.find { it.handler == "logsOff" && it.delay == 1800 }
    f.jobs.clear()
    f.settings.logLevel = "4"
    f.settings.logLevelTime = "0"
    f.driver.checkLogLevel()
    assert f.jobs.empty
    f.settings.logLevel = "1"
    f.settings.remove("logLevelTime")
    f.driver.checkLogLevel()
    assert f.settings.logLevelTime == "30"
    assert f.jobs.empty
}

int failures = 0
cases.each { entry ->
    try {
        entry.body()
        println "PASS ${entry.name}"
    } catch (Throwable failure) {
        failures++
        println "FAIL ${entry.name}: ${failure.class.simpleName}: ${failure.message?.readLines()?.find { it }}"
        if (!(failure instanceof AssertionError)) failure.printStackTrace(System.out)
    }
}
println "${cases.size() - failures}/${cases.size()} passed (actual driver methods; Hubitat interfaces mocked)"
assert failures == 0
