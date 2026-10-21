/**
 * End-to-end flows through the app and the real drivers, with the hub, Auth0
 * and the ecobee API faked: sign-in with and without a verification code,
 * rejected sign-ins, summary-first polling (no fetch when nothing changed,
 * runtime-only fetch, full fetch), device creation, gathered writes with a
 * read-back, token renewal on expiry and on auth errors, an ended sign-in,
 * 429 backoff, and no secrets in logs. State goes through JSON after each
 * step (hub.settle()), as on a hub.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import static Check.check

@Field File root = new File(getClass().protectionDomain.codeSource.location.path).parentFile.parentFile
@Field String EMAIL = "owner@example.com"
@Field String PASS = "s3cret-Pa55"
@Field String CB = "https://www.ecobee.com/home/authCallback"

@Field Hub hub
@Field def app
@Field int answered = 0
@Field Map thermo
@Field Map auth0                 // fake Auth0 behaviour switches
@Field List seen = []            // every request [method, path, params]
@Field int tokenSerial = 0

void setProperty(String name, Object v) {
    if (metaClass.hasProperty(this, name)) metaClass.setProperty(this, name, v)
    else super.setProperty(name, v)
}

void fresh() {
    hub = new Hub(root)
    hub.clock = 1_792_000_000_000L
    app = hub.app()
    app.RUNTIME.remove(app.app.id)
    app.settings.logEnable = true
    app.settings.pollMinutes = "3"
    app.settings.holdType = "nextTransition"
    answered = 0
    seen = []
    tokenSerial = 0
    thermo = Fixtures.thermostat()
    auth0 = [mfa: null, wrongPassword: false, wrongCodes: 0, refreshError: null]
}

List httpCalls() { app.calls.findAll { it[0] in ["asynchttpGet", "asynchttpPost"] } }

FakeResp ok(def json) { new FakeResp(status: 200, data: JsonOutput.toJson(json)) }
FakeResp redirect(String loc, List cookies = []) {
    Map h = ["Location": loc]
    if (cookies) h["Set-Cookie"] = cookies.collect { new FakeHeaderEntry(name: "Set-Cookie", value: it) }
    new FakeResp(status: 302, data: "", headers: h)
}
FakeResp html(int status, String body) { new FakeResp(status: status, data: body) }

Map form(Map p) {
    (p.body as String).split("&").collectEntries { kv ->
        List a = kv.split("=", 2) as List
        [(URLDecoder.decode(a[0], "UTF-8")): URLDecoder.decode(a[1] ?: "", "UTF-8")]
    }
}

/** Fake Auth0 and ecobee: one answer per request. */
FakeResp serve(List call) {
    String method = call[0] == "asynchttpGet" ? "GET" : "POST"
    Map p = call[2]
    String path = p.path
    seen << [method, path, p]
    Map q = p.query ?: [:]
    String cookie = p.headers?.Cookie ?: ""
    if (p.uri == "https://auth.ecobee.com") {
        switch (path) {
            case "/authorize":
                assert q.client_id && q.code_challenge_method == "S256" && q.scope.contains("offline_access")
                auth0.challenge = q.code_challenge
                return redirect("/u/login/identifier?state=S1", ["did=D1; Path=/; Secure", "did_compat=D1; Path=/"])
            case "/u/login/identifier":
                assert cookie.contains("did=D1")
                assert form(p).username == EMAIL && form(p).state == "S1"
                return redirect("/u/login/password?state=S2", ["auth0=A1; Path=/; HttpOnly"])
            case "/u/login/password":
                assert cookie.contains("auth0=A1")
                if (auth0.wrongPassword || form(p).password != PASS) return html(400, '<div data-error-code="wrong-credentials"></div>')
                return redirect("https://auth.ecobee.com/authorize/resume?state=S3", ["auth0=A2; Path=/"])
            case "/authorize/resume":
                assert cookie.contains("auth0=A2")
                if (auth0.mfa == "push") return redirect("/u/mfa-push-challenge-push?state=M1")
                if (auth0.mfa && !auth0.mfaDone) return redirect("/u/mfa-${auth0.mfa}-challenge?state=M1")
                return redirect("${CB}?code=CODE1&state=x")
            case ~/\/u\/mfa-(otp|sms)-challenge/:
                if (method == "GET") {
                    auth0.challengeLoaded = true
                    return html(200, "<form>enter code</form>")
                }
                assert form(p).state == "M1"
                if (auth0.wrongCodes > 0) {
                    auth0.wrongCodes--
                    return html(400, '<div data-error-code="invalid-code"></div>')
                }
                assert form(p).code == "123456"
                auth0.mfaDone = true
                return redirect("/authorize/resume?state=S4")
            case "/oauth/token":
                Map f = form(p)
                if (f.grant_type == "authorization_code") {
                    assert f.code == "CODE1"
                    assert app.pkceChallenge(f.code_verifier) == auth0.challenge
                    return ok([access_token: "AT${++tokenSerial}", refresh_token: "RT${tokenSerial}", expires_in: 3600])
                }
                assert f.grant_type == "refresh_token"
                if (auth0.refreshDown) return new FakeResp(status: 503, data: "", errorMessage: "Service Unavailable")
                if (auth0.refreshError) return new FakeResp(status: 403, data: JsonOutput.toJson([error: auth0.refreshError, error_description: "Unknown or invalid refresh token."]))
                assert f.refresh_token == "RT${tokenSerial}"
                return ok([access_token: "AT${++tokenSerial}", refresh_token: "RT${tokenSerial}", expires_in: 3600])
        }
        throw new AssertionError("unexpected auth request ${method} ${path}")
    }
    assert p.uri == "https://api.ecobee.com"
    if (auth0.expireApi) {
        auth0.expireApi = false
        return new FakeResp(status: 500, data: JsonOutput.toJson([status: [code: 14, message: "Authentication token has expired. Refresh your tokens."]]))
    }
    if (auth0.rateLimit) return new FakeResp(status: 429, data: "")
    if (auth0.status1 && auth0.status1 > 0) {
        auth0.status1--
        return new FakeResp(status: 500, data: JsonOutput.toJson([status: [code: 1, message: "Authentication failed. Unexpected error."]]))
    }
    if (auth0.failFetch && path == "/1/thermostat" && method == "GET") {
        auth0.failFetch = false
        return new FakeResp(status: 504, data: "", errorMessage: "Gateway Timeout")
    }
    assert p.headers.Authorization == "Bearer AT${tokenSerial}"
    if (path == "/1/thermostatSummary") {
        Map rt = thermo.runtime
        return ok(Fixtures.summary(thermo.thermostatRev, rt.runtimeRev, thermo.equipment ?: "heatPump,fan"))
    }
    if (path == "/1/thermostat" && method == "GET") {
        Map sel = new JsonSlurper().parseText(q.json).selection
        return ok(Fixtures.thermostatAnswer(sel, thermo))
    }
    if (path == "/1/thermostat" && method == "POST") {
        auth0.lastWrite = new JsonSlurper().parseText(p.body)
        return ok([status: [code: 0, message: ""]])
    }
    throw new AssertionError("unexpected api request ${method} ${path}")
}

/** Answer every unanswered request, including ones the answers cause. */
void drain() {
    hub.settle()
    while (answered < httpCalls().size()) {
        List c = httpCalls()[answered++]
        FakeResp r = serve(c)
        app."${c[1]}"(r, c[3])
        hub.settle()
    }
}

/** As on a hub: the button handler runs, the typed inputs are saved, then the page redraws. */
void press(String btn, Map typed = [:]) {
    app.appButtonHandler(btn)
    hub.settle()
    app.settings.putAll(typed)
    app.mainPage()
    hub.settle()
}

void signIn() {
    app.settings.email = EMAIL
    app.settings.password = PASS
    press("btnSignIn")
    drain()
}

/** Run the scheduled poll and answer it. */
void poll() {
    app.calls.removeAll { it[0] == "runIn" && it[2] == "poll" }
    app.poll()
    drain()
}

List requestPaths() { seen.collect { "${it[0]} ${it[1]}" } }

def thermostatDevice() { app.getChildDevice("ecobee:${Fixtures.ID}") }

// ---------------------------------------------------------------- sign-in

check("sign-in without a code: tokens kept, password cleared, polling scheduled") {
    fresh()
    signIn()
    assert requestPaths().take(5) == ["GET /authorize", "POST /u/login/identifier", "POST /u/login/password", "GET /authorize/resume", "POST /oauth/token"]
    // Discovery starts at once, without waiting for a timer.
    assert requestPaths().drop(5) == ["GET /1/thermostatSummary", "GET /1/thermostat"]
    assert !app.callsNamed("runIn").any { it[2] == "poll" }
    assert app.state.auth.refresh == "RT1" && app.state.auth.access == "AT1"
    assert app.state.auth.account == EMAIL
    assert app.state.login.step == "done"
    assert !app.settings.containsKey("password")
    assert app.callsNamed("schedule").last()[2] == "poll"
    assert app.callsNamed("schedule").last()[1] ==~ /\d+ \d+\/3 \* \? \* \*/
}

check("sign-in with an authenticator code, after one wrong code") {
    fresh()
    auth0.mfa = "otp"
    auth0.wrongCodes = 1
    signIn()
    assert app.state.login.step == "mfa" && app.state.login.mfaKind == "otp"
    assert auth0.challengeLoaded
    assert app.mfaPrompt("otp").contains("authenticator")
    app.settings.mfaCode = "000000"
    press("btnMfa")
    drain()
    assert app.state.login.step == "mfa"
    assert app.state.login.error.contains("wasn't accepted")
    app.settings.mfaCode = "123 456"
    press("btnMfa")
    drain()
    assert app.state.auth.refresh == "RT1"
    assert !app.settings.containsKey("mfaCode")
}

check("sign-in with a text-message code") {
    fresh()
    auth0.mfa = "sms"
    signIn()
    assert app.state.login.mfaKind == "sms"
    app.settings.mfaCode = "123456"
    press("btnMfa")
    drain()
    assert app.state.auth.refresh == "RT1"
}

check("one press is enough: inputs saved after the button handler still sign in") {
    fresh()
    press("btnSignIn", [email: EMAIL, password: PASS])
    drain()
    assert app.signedIn()
    assert app.state.pendingButton == null
}

check("one press of Submit code uses the code typed just before it") {
    fresh()
    auth0.mfa = "otp"
    signIn()
    press("btnMfa", [mfaCode: "123456"])
    drain()
    assert app.signedIn()
    assert !app.log.at("warn").any { it.contains("digits") }
}

check("page refreshes during sign-in do not start it twice") {
    fresh()
    press("btnSignIn", [email: EMAIL, password: PASS])
    app.mainPage()
    app.mainPage()
    assert httpCalls().size() == 1
}

check("wrong password: clear message, password cleared, not signed in") {
    fresh()
    auth0.wrongPassword = true
    signIn()
    assert !app.signedIn()
    assert app.state.login.error == "ecobee didn't accept that password."
    assert !app.settings.containsKey("password")
}

check("push verification is refused with what to change") {
    fresh()
    auth0.mfa = "push"
    signIn()
    assert !app.signedIn()
    assert app.state.login.error.contains("authenticator app or text message")
}

check("a stale sign-in answer is ignored after starting over") {
    fresh()
    app.settings.email = EMAIL
    app.settings.password = PASS
    press("btnSignIn")
    hub.settle()
    List first = httpCalls()[0]
    app.appButtonHandler("btnCancel")
    hub.settle()
    app."${first[1]}"(serve(first), first[3])
    assert app.state.login == null
}

check("missing email or password asks for them without a request") {
    fresh()
    press("btnSignIn")
    assert httpCalls().isEmpty()
    assert app.state.login.error.contains("Enter your ecobee email")
}

// ---------------------------------------------------------------- polling

check("first update right after sign-in: summary, then one full fetch; devices created with values") {
    fresh()
    signIn()
    assert requestPaths().drop(5) == ["GET /1/thermostatSummary", "GET /1/thermostat"]
    Map sel = new JsonSlurper().parseText(seen[6][2].query.json).selection
    assert sel.selectionMatch == Fixtures.ID && sel.includeSettings && sel.includeProgram && sel.includeEvents && sel.includeRuntime
    assert app.getChildDevices()*.deviceNetworkId.sort() == ["ecobee:${Fixtures.ID}", "ecobee:${Fixtures.ID}:rs-100", "ecobee:${Fixtures.ID}:rs2-101"]*.toString().sort()
    def t = thermostatDevice()
    assert t.label == "Main Floor"
    assert t.current.temperature == 70.5 && t.current.thermostatOperatingState == "heating"
    assert t.current.heatingSetpoint == 68 && t.current.thermostatMode == "heat"
    def bed = app.getChildDevice("ecobee:${Fixtures.ID}:rs-100")
    assert bed.label == "Bedroom" && bed.current.temperature == 68.8 && bed.current.motion == "inactive"
    assert app.getChildDevice("ecobee:${Fixtures.ID}:rs2-101").current.healthStatus == "offline"
}

check("nothing changed: only the summary is requested") {
    fresh()
    signIn()
    poll()
    seen.clear()
    poll()
    assert requestPaths() == ["GET /1/thermostatSummary"]
}

check("equipment change alone updates the operating state without a fetch") {
    fresh()
    signIn()
    poll()
    seen.clear()
    thermo.equipment = "compCool1,fan"
    thermo.settings.hvacMode = "heat"
    poll()
    assert requestPaths() == ["GET /1/thermostatSummary"]
    assert thermostatDevice().current.thermostatOperatingState == "cooling"
    assert thermostatDevice().current.equipmentStatus == "compCool1,fan"
}

check("runtime revision change: runtime and sensors only") {
    fresh()
    signIn()
    poll()
    seen.clear()
    thermo.runtime.runtimeRev = "261017070400"
    thermo.runtime.actualTemperature = 712
    thermo.remoteSensors[1].capability[0].value = "700"
    poll()
    assert requestPaths() == ["GET /1/thermostatSummary", "GET /1/thermostat"]
    Map sel = new JsonSlurper().parseText(seen[1][2].query.json).selection
    assert sel.includeRuntime && sel.includeSensors && !sel.includeSettings && !sel.includeProgram
    assert thermostatDevice().current.temperature == 71.2
    assert thermostatDevice().current.currentClimate == "Hold"
    assert app.getChildDevice("ecobee:${Fixtures.ID}:rs-100").current.temperature == 70.0
}

check("thermostat revision change: full fetch picks up a mode change") {
    fresh()
    signIn()
    poll()
    seen.clear()
    thermo.thermostatRev = "261017071000"
    thermo.settings.hvacMode = "cool"
    poll()
    Map sel = new JsonSlurper().parseText(seen[1][2].query.json).selection
    assert sel.includeSettings
    assert thermostatDevice().current.thermostatMode == "cool"
}

check("device refresh forces a full fetch") {
    fresh()
    signIn()
    poll()
    seen.clear()
    thermostatDevice().refresh()
    drain()
    assert requestPaths() == ["GET /1/thermostatSummary", "GET /1/thermostat"]
}

check("deselecting a thermostat removes it and its sensors") {
    fresh()
    signIn()
    poll()
    page()
    app.state.names["999"] = "Other"
    app.settings.thermostats = ["999"]
    app.syncChildren()
    assert !app.getChildDevices().any { it.deviceNetworkId.startsWith("ecobee:${Fixtures.ID}") }
}

check("unchecking every room sensor removes the sensor devices only") {
    fresh()
    signIn()
    page()
    app.settings.remove("sensors")   // Hubitat drops an emptied multi-select
    app.updated()
    drain()
    assert app.getChildDevices()*.deviceNetworkId == ["ecobee:${Fixtures.ID}".toString()]
}

// ---------------------------------------------------------------- app page

/** Render the page; returns [refreshInterval, paragraphs, input names]. */
Map page() {
    app.calls.removeAll { it[0] in ["paragraph", "input"] }
    Map m = app.mainPage()
    [refresh: m.refreshInterval, text: app.callsNamed("paragraph").collect { it[1] }.join("\n"), inputs: app.callsNamed("input").collect { it[1] }]
}

/** Answer requests until the next unanswered one is for this path. */
void drainUntil(String path) {
    hub.settle()
    while (answered < httpCalls().size() && httpCalls()[answered][2].path != path) {
        List c = httpCalls()[answered++]
        app."${c[1]}"(serve(c), c[3])
        hub.settle()
    }
}

check("after sign-in the page refreshes until the devices exist, then lists them") {
    fresh()
    press("btnSignIn", [email: EMAIL, password: PASS])
    drainUntil("/1/thermostatSummary")
    Map pg = page()
    assert pg.refresh == 2
    assert pg.text.contains("Looking for thermostats")
    drain()
    pg = page()
    assert pg.refresh == 0
    assert pg.text.contains("<b>Devices: 1 thermostat, 2 room sensors</b>")
    assert pg.text =~ /<a href='\/device\/edit\/\d+' target='_blank'>Main Floor<\/a>: thermostat, 70.5°F/
    assert pg.text.contains(">Bedroom</a>: room sensor, 68.8°F")
    assert pg.text.contains(">Basement</a>: room sensor, no reading")
    assert pg.text.indexOf("Main Floor") < pg.text.indexOf("Basement")
    assert app.pickerOptions(app.state.names) == [(Fixtures.ID): "Main Floor (2 room sensors)"]
}

check("before the first Done: thermostats are read and shown, devices are added on Done") {
    fresh()
    app.app.installationState = "INCOMPLETE"
    press("btnSignIn", [email: EMAIL, password: PASS])
    drainUntil("/1/thermostat")
    assert page().refresh == 2
    drain()
    assert app.getChildDevices().isEmpty()
    assert !app.callsNamed("schedule")
    Map pg = page()
    assert pg.refresh == 0
    assert pg.text.contains("<b>To add: 1 thermostat and 2 room sensors</b><br>Main Floor: thermostat, 70.5°F<br>")
    assert pg.text.contains("Basement: room sensor, no reading")
    assert pg.text.contains("Bedroom: room sensor, 68.8°F")
    assert pg.text.contains("Press <b>Done</b> to add them as devices.")
    // Done: Hubitat marks the app installed and calls installed().
    app.app.installationState = "COMPLETE"
    app.installed()
    drain()
    assert app.getChildDevices().size() == 3
    assert thermostatDevice().current.temperature == 70.5
    assert app.callsNamed("schedule")
    assert page().text.contains("<b>Devices: 1 thermostat, 2 room sensors</b>")
}

check("pickers start with every thermostat and room sensor checked, labelled by thermostat") {
    fresh()
    app.app.installationState = "INCOMPLETE"
    signIn()
    page()
    assert app.state.picksSet
    assert app.settings.thermostats == [Fixtures.ID]
    assert (app.settings.sensors as List).sort() == ["${Fixtures.ID}|rs:100", "${Fixtures.ID}|rs2:101"]*.toString().sort()
    assert app.sensorOptions() == [("${Fixtures.ID}|rs:100".toString()): "Bedroom (on Main Floor)", ("${Fixtures.ID}|rs2:101".toString()): "Basement (on Main Floor)"]
}

check("before the first Done, an unchecked sensor is left out and counted") {
    fresh()
    app.app.installationState = "INCOMPLETE"
    signIn()
    page()
    app.settings.sensors = ["${Fixtures.ID}|rs:100".toString()]
    String text = page().text
    assert text.contains("<b>To add: 1 thermostat and 1 room sensor</b>")
    assert text.contains("Bedroom: room sensor") && !text.contains("Basement")
    assert text.contains("1 room sensor not picked.")
    app.app.installationState = "COMPLETE"
    app.installed()
    drain()
    assert app.getChildDevices()*.deviceNetworkId.sort() == ["ecobee:${Fixtures.ID}", "ecobee:${Fixtures.ID}:rs-100"]*.toString()
}

check("after setup, unchecking a sensor says Done applies it, and Done removes it") {
    fresh()
    signIn()
    page()
    assert !page().text.contains("apply your changes")
    app.settings.sensors = ["${Fixtures.ID}|rs2:101".toString()]
    assert page().text.contains("Press <b>Done</b> to apply your changes.")
    app.updated()
    drain()
    assert app.getChildDevice("ecobee:${Fixtures.ID}:rs-100") == null
    assert app.getChildDevice("ecobee:${Fixtures.ID}:rs2-101") != null
    assert !page().text.contains("apply your changes")
    // Checking it again brings it back.
    app.settings.sensors = ["${Fixtures.ID}|rs:100", "${Fixtures.ID}|rs2:101"]*.toString()
    app.updated()
    drain()
    assert app.getChildDevice("ecobee:${Fixtures.ID}:rs-100") != null
}

check("an earlier install keeps its devices: picked thermostat stays, sensors follow the old switch") {
    fresh()
    signIn()
    // As set up by the previous version: thermostat picked, sensors switch on, no sensor picker.
    app.state.picksSet = null
    app.settings.thermostats = [Fixtures.ID]
    app.settings.createSensors = true
    app.settings.remove("sensors")
    page()
    assert (app.settings.sensors as List).sort() == ["${Fixtures.ID}|rs:100", "${Fixtures.ID}|rs2:101"]*.toString().sort()
    assert !app.settings.containsKey("createSensors")
    app.updated()
    drain()
    assert app.getChildDevices().size() == 3
}

check("an earlier install with the sensors switch off starts with no sensors checked") {
    fresh()
    app.settings.createSensors = false
    signIn()
    assert app.getChildDevices().size() == 1
    page()
    assert app.settings.sensors == null && app.state.picksSet
    app.updated()
    drain()
    assert app.getChildDevices().size() == 1
}

check("a thermostat added to the account later shows up unchecked") {
    fresh()
    signIn()
    page()
    app.state.names["999"] = "Upstairs"
    assert app.selectedIds() == [Fixtures.ID]
    assert app.pickerOptions(app.state.names)["999"] == "Upstairs"
}

check("the page stops refreshing after a minute and offers to look again") {
    fresh()
    signIn()
    thermo.identifier = "none"
    Map empty = [revisionList: [], statusList: [], status: [code: 0, message: ""]]
    app.calls.removeAll { it[0] == "runIn" && it[2] == "poll" }
    app.poll()
    hub.settle()
    List c = httpCalls()[answered++]
    app."${c[1]}"(ok(empty), c[3])
    hub.settle()
    assert page().refresh == 2
    hub.clock += 61 * 1000L
    Map pg = page()
    assert pg.refresh == 0
    assert pg.text.contains("No thermostats found")
    app.appButtonHandler("btnFind")
    drain()
    pg = page()
    assert pg.refresh == 0
    assert pg.text.contains("Main Floor")
}

check("while ecobee checks the code, the page shows progress only, not the sign-in form") {
    fresh()
    auth0.mfa = "otp"
    signIn()
    assert page().inputs.containsAll(["mfaCode", "btnMfa", "btnCancel"])
    app.settings.mfaCode = "123456"
    app.appButtonHandler("btnMfa")
    hub.settle()
    Map pg = page()
    assert pg.refresh == 2
    assert pg.text.contains("Checking your code with ecobee")
    assert pg.inputs.isEmpty()
    assert !pg.text.contains("email and password")
    drain()
    pg = page()
    assert pg.text.contains("Signed in as")
}

check("while signing in the page refreshes; the sign-in form has no refresh otherwise") {
    fresh()
    assert page().refresh == 0
    app.settings.email = EMAIL
    app.settings.password = PASS
    press("btnSignIn")
    hub.settle()
    Map pg = page()
    assert pg.refresh == 2 && pg.text.contains("Signing in")
    assert pg.inputs.isEmpty()
}

// ---------------------------------------------------------------- writes

check("rapid setpoint changes go out as one hold, shown at once, read back after") {
    fresh()
    signIn()
    poll()
    seen.clear()
    def t = thermostatDevice()
    t.setHeatingSetpoint(69)
    t.setHeatingSetpoint(70)
    t.setCoolingSetpoint(77)
    assert t.current.heatingSetpoint == 70 && t.current.coolingSetpoint == 77
    assert app.callsNamed("runInMillis").size() == 3
    app.flushWrites()
    drain()
    assert requestPaths() == ["POST /1/thermostat"]
    assert auth0.lastWrite.functions == [[type: "setHold", params: [holdType: "nextTransition", heatHoldTemp: 700, coolHoldTemp: 770]]]
    assert app.callsNamed("runIn").any { it[2] == "confirmWrites" }
    thermo.runtime.desiredHeat = 700
    thermo.runtime.desiredCool = 770
    app.confirmWrites()
    drain()
    Map sel = new JsonSlurper().parseText(seen[1][2].query.json).selection
    assert seen[1][1] == "/1/thermostat" && sel.includeSettings
    assert t.current.heatingSetpoint == 70
}

check("comfort profile then resume: only the resume is sent") {
    fresh()
    signIn()
    poll()
    def t = thermostatDevice()
    t.setClimate("sleep")
    assert t.current.currentClimate == "Sleep"
    t.resumeProgram()
    app.flushWrites()
    drain()
    assert auth0.lastWrite.functions == [[type: "resumeProgram", params: [resumeAll: true]]]
}

check("custom comfort profile by name; unknown names are refused") {
    fresh()
    signIn()
    poll()
    def t = thermostatDevice()
    t.setClimate("work from home")
    app.flushWrites()
    drain()
    assert auth0.lastWrite.functions[0].params.holdClimateRef == "smart1"
    int before = httpCalls().size()
    t.setClimate("Gym")
    app.flushWrites()
    drain()
    assert httpCalls().size() == before
    assert app.log.at("warn").any { it.contains("no comfort profile named Gym") }
}

check("modes: emergency heat maps to aux, unsupported modes are refused") {
    fresh()
    signIn()
    poll()
    def t = thermostatDevice()
    t.emergencyHeat()
    app.flushWrites()
    drain()
    assert auth0.lastWrite.thermostat == [settings: [hvacMode: "auxHeatOnly"]]
    thermo.settings.hasHeatPump = false
    thermo.settings.coolStages = 0
    thermo.thermostatRev = "x2"
    poll()
    int before = httpCalls().size()
    t.cool()
    app.flushWrites()
    drain()
    assert httpCalls().size() == before
}

check("fan: circulate sets minutes per hour; auto clears them") {
    fresh()
    signIn()
    poll()
    app.settings.circulateMinutes = 25
    def t = thermostatDevice()
    t.fanCirculate()
    app.flushWrites()
    drain()
    assert auth0.lastWrite.thermostat == [settings: [fanMinOnTime: 25]]
    thermo.settings.fanMinOnTime = 25
    thermo.thermostatRev = "x3"
    poll()
    assert t.current.thermostatFanMode == "circulate"
    t.fanAuto()
    app.flushWrites()
    drain()
    assert auth0.lastWrite.thermostat == [settings: [fanMinOnTime: 0]]
}

// ---------------------------------------------------------------- tokens and failures

check("an expired access token is renewed before the poll; rotated refresh token kept") {
    fresh()
    signIn()
    hub.clock += 3600 * 1000L
    seen.clear()
    poll()
    assert requestPaths().take(2) == ["POST /oauth/token", "GET /1/thermostatSummary"]
    assert app.state.auth.refresh == "RT2" && app.state.auth.access == "AT2"
}

check("ecobee status 14 mid-poll: renew and retry once") {
    fresh()
    signIn()
    poll()
    seen.clear()
    auth0.expireApi = true
    poll()
    assert requestPaths() == ["GET /1/thermostatSummary", "POST /oauth/token", "GET /1/thermostatSummary"]
    assert app.state.fails == 0
}

check("an ended sign-in: devices offline, polling stops, sign-in page shown") {
    fresh()
    signIn()
    poll()
    auth0.refreshError = "invalid_grant"
    hub.clock += 3600 * 1000L
    poll()
    assert app.state.auth.needsLogin
    assert !app.signedIn()
    assert app.callsNamed("unschedule").any { it[1] == "poll" }
    assert thermostatDevice().current.healthStatus == "offline"
    int before = httpCalls().size()
    poll()
    assert httpCalls().size() == before
    // Signing in again brings everything back.
    auth0.refreshError = null
    tokenSerial = 0
    signIn()
    assert app.signedIn()
    assert thermostatDevice().current.healthStatus == "online"
}

check("429: backoff skips polls; three failures mark devices offline") {
    fresh()
    signIn()
    poll()
    auth0.rateLimit = true
    poll()
    assert app.state.backoffUntil > hub.clock
    int before = httpCalls().size()
    poll()
    assert httpCalls().size() == before
    hub.clock = app.state.backoffUntil + 1
    poll()
    hub.clock = app.state.backoffUntil + 1
    poll()
    assert app.state.fails == 3
    assert thermostatDevice().current.healthStatus == "offline"
    auth0.rateLimit = false
    hub.clock = app.state.backoffUntil + 1
    poll()
    assert app.state.fails == 0
    assert thermostatDevice().current.healthStatus == "online"
}

// ---------------------------------------------------------------- review fixes

check("ecobee status 1 twice in a row backs off instead of ending the sign-in") {
    fresh()
    signIn()
    poll()
    auth0.status1 = 2
    poll()
    assert app.signedIn()
    assert !app.callsNamed("unschedule").any { it[1] == "poll" }
    assert app.state.backoffUntil > hub.clock
    assert app.state.fails == 1
    hub.clock = app.state.backoffUntil + 1
    poll()
    assert app.state.fails == 0
}

check("the password is cleared as soon as ecobee accepts it, before the code step") {
    fresh()
    auth0.mfa = "otp"
    signIn()
    assert app.state.login.step == "mfa"
    assert !app.settings.containsKey("password")
}

check("a sign-in that stops getting answers says so") {
    fresh()
    app.settings.email = EMAIL
    app.settings.password = PASS
    press("btnSignIn")
    hub.clock += 91 * 1000L
    Map pg = page()
    assert pg.text.contains("ecobee didn't answer the sign-in in time")
    assert app.state.login.step == "idle"
    assert !app.settings.containsKey("password")
}

check("before the pickers are set up, a thermostat missing from one summary keeps its device") {
    fresh()
    signIn()
    assert !app.state.picksSet
    String saved = thermo.identifier
    Map empty = [revisionList: [], statusList: [], status: [code: 0, message: ""]]
    app.state.forceFull = true
    app.calls.removeAll { it[0] == "runIn" && it[2] == "poll" }
    app.poll()
    hub.settle()
    List c = httpCalls()[answered++]
    app."${c[1]}"(ok(empty), c[3])
    hub.settle()
    app.syncChildren()
    assert app.getChildDevices().size() == 3
}

check("a sensor ecobee didn't list this time keeps its device; an unchecked one goes") {
    fresh()
    signIn()
    page()
    List all = new ArrayList(thermo.remoteSensors)
    thermo.remoteSensors = all.findAll { it.id != "rs:100" }
    thermo.runtime.runtimeRev = "r9"
    poll()
    assert app.getChildDevice("ecobee:${Fixtures.ID}:rs-100") != null
    thermo.remoteSensors = all
    thermo.runtime.runtimeRev = "r10"
    poll()
    app.settings.sensors = ["${Fixtures.ID}|rs:100".toString()]
    app.updated()
    drain()
    assert app.getChildDevice("ecobee:${Fixtures.ID}:rs2-101") == null
}

check("a change waiting on a failed renewal is reported and read back later") {
    fresh()
    signIn()
    poll()
    def t = thermostatDevice()
    hub.clock += 3600 * 1000L
    auth0.refreshDown = true
    t.setHeatingSetpoint(72)
    assert t.current.heatingSetpoint == 72
    app.flushWrites()
    drain()
    assert auth0.lastWrite == null
    assert app.log.at("warn").any { it.contains("change wasn't sent") }
    assert app.callsNamed("runIn").any { it[1] == 60 && it[2] == "confirmWrites" }
    auth0.refreshDown = false
    app.confirmWrites()
    drain()
    assert t.current.heatingSetpoint == 68
}

check("gathered changes are sent once: a change after a flush starts a new batch") {
    fresh()
    signIn()
    poll()
    def t = thermostatDevice()
    t.setHeatingSetpoint(69)
    app.flushWrites()
    drain()
    assert auth0.lastWrite.functions[0].params.heatHoldTemp == 690
    t.setCoolingSetpoint(78)
    app.flushWrites()
    drain()
    Map p = auth0.lastWrite.functions[0].params
    assert p.coolHoldTemp == 780 && p.heatHoldTemp == 680
    int writes = seen.count { it[0] == "POST" && it[1] == "/1/thermostat" }
    app.flushWrites()
    drain()
    assert seen.count { it[0] == "POST" && it[1] == "/1/thermostat" } == writes
}

check("after an outage, devices get their own health back, not all online") {
    fresh()
    signIn()
    poll()
    def basement = app.getChildDevice("ecobee:${Fixtures.ID}:rs2-101")
    assert basement.current.healthStatus == "offline"
    auth0.rateLimit = true
    3.times {
        hub.clock = Math.max(hub.clock, (app.state.backoffUntil ?: 0) as long) + 1
        poll()
    }
    assert thermostatDevice().current.healthStatus == "offline"
    auth0.rateLimit = false
    hub.clock = app.state.backoffUntil + 1
    poll()
    assert thermostatDevice().current.healthStatus == "online"
    assert basement.current.healthStatus == "offline"
    assert app.getChildDevice("ecobee:${Fixtures.ID}:rs-100").current.healthStatus == "online"
}

check("a device Refresh whose fetch fails is retried on the next poll") {
    fresh()
    signIn()
    poll()
    auth0.failFetch = true
    thermostatDevice().refresh()
    drain()
    assert app.state.forceFull
    hub.clock = app.state.backoffUntil + 1
    seen.clear()
    poll()
    assert requestPaths() == ["GET /1/thermostatSummary", "GET /1/thermostat"]
    assert new JsonSlurper().parseText(seen[1][2].query.json).selection.includeSettings
    assert !app.state.forceFull
}

check("signing in to a different account starts the picks over; the same account keeps them") {
    fresh()
    signIn()
    page()
    app.settings.sensors = ["${Fixtures.ID}|rs:100".toString()]
    app.signOut()
    tokenSerial = 0
    signIn()
    assert app.state.picksSet && app.settings.sensors == ["${Fixtures.ID}|rs:100".toString()]
    app.signOut()
    tokenSerial = 0
    EMAIL = "other@example.com"
    try {
        signIn()
    } finally {
        EMAIL = "owner@example.com"
    }
    assert !app.state.picksSet
    assert app.settings.sensors == null && app.settings.thermostats == null
    assert app.state.account == "other@example.com"
}

check("a fetch applies only the thermostats it returned") {
    fresh()
    signIn()
    poll()
    app.state.tstat["999"] = app.state.tstat[Fixtures.ID] + [name: "Ghost"]
    app.settings.thermostats = [Fixtures.ID, "999"]
    app.syncChildren()
    def ghost = app.getChildDevice("ecobee:999")
    int before = ghost.events.size()
    thermo.runtime.runtimeRev = "r11"
    poll()
    assert ghost.events.size() == before
}

check("no password, token, code or cookie value appears in any log") {
    fresh()
    auth0.mfa = "otp"
    signIn()
    app.settings.mfaCode = "123456"
    press("btnMfa")
    drain()
    poll()
    hub.clock += 3600 * 1000L
    poll()
    thermostatDevice().setHeatingSetpoint(70)
    app.flushWrites()
    drain()
    List all = (app.log.lines + app.getChildDevices().collectMany { it.driver.log.lines })*.getAt(1)
    assert all.any { it.contains("Sign-in") }
    List secrets = [PASS, "123456", "AT1", "AT2", "RT1", "RT2", "CODE1", "D1", "A1", "A2"]
    assert !all.any { line -> secrets.any { line.contains(it) } }
}

Check.finish()
