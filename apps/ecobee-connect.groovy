/**
 * Ecobee Connect: Hubitat integration for ecobee thermostats and room sensors,
 * signed in with the ecobee account's email and password (no developer API key).
 *
 * Sign-in follows ecobee's own web login (Auth0 at auth.ecobee.com: authorize
 * with PKCE, email, password, optional authenticator-app or text-message code,
 * then the token exchange), the same flow Home Assistant's ecobee integration
 * and the Homey "ecobee Comfort" app use. Only the refresh token is kept; the
 * password is cleared from the app's settings once sign-in finishes.
 *
 * Updates are summary-first: each poll asks ecobee only for the thermostat
 * summary, a few revision strings per thermostat plus what equipment is
 * running. The full thermostat is fetched only when a revision changed, and
 * only the parts that changed: settings, program and events when the
 * thermostat revision moved; runtime and sensors when the runtime revision
 * moved. Commands from devices are gathered for a moment and sent as one
 * request per thermostat, then read back a few seconds later.
 *
 * All ecobee mapping lives in this file as plain functions of the API answers,
 * so the device drivers stay thin.
 *
 * Copyright (c) 2026 Digital Homesteaders. MIT License; see LICENSE.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

definition(
    name: "Ecobee Connect",
    namespace: "ecobeeconnect",
    author: "Digital Homesteaders",
    description: "ecobee thermostats and room sensors, signed in with your ecobee email and password.",
    category: "Convenience",
    singleInstance: true,
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/digital-homesteaders/hubitat-ecobee/main/apps/ecobee-connect.groovy"
)

preferences {
    page(name: "mainPage")
}

// ---------------------------------------------------------------- constants

@Field static final String AUTH_HOST = "https://auth.ecobee.com"
@Field static final String API_HOST = "https://api.ecobee.com"
// ecobee's public web client, its callback and API audience (as ecobee.com uses them).
@Field static final String CLIENT_ID = "183eORFPlXyz9BbDZwqexHPBQoVjgadh"
@Field static final String REDIRECT_URI = "https://www.ecobee.com/home/authCallback"
@Field static final String AUDIENCE = "https://prod.ecobee.com/api/v1"
// offline_access is what makes Auth0 issue a refresh token.
@Field static final String SCOPE = "openid smartRead smartWrite piiRead piiWrite offline_access"
@Field static final String BROWSER_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"

@Field static final String THERMOSTAT_DRIVER = "Ecobee Connect Thermostat"
@Field static final String SENSOR_DRIVER = "Ecobee Connect Sensor"

// Seconds per request.
@Field static final int REQUEST_TIMEOUT = 20
// ecobee asks for no polling faster than every 3 minutes.
@Field static final Map POLL_CHOICES = ["3": "3 minutes (ecobee's minimum)", "5": "5 minutes", "10": "10 minutes", "15": "15 minutes"]
// After sign-in the page refreshes up to this long while it looks for thermostats.
@Field static final long DISCOVER_MS = 60 * 1000L
// A sign-in still working after this long is shown as failed.
@Field static final long LOGIN_TIMEOUT_MS = 90 * 1000L
// Redirects and prompt pages one sign-in may pass through.
@Field static final int LOGIN_MAX_HOPS = 12
// Refresh the access token when it has less than this left.
@Field static final long TOKEN_MARGIN_MS = 5 * 60 * 1000L
// A refresh still unanswered after this long no longer blocks a new one.
@Field static final long REFRESH_STALE_MS = 60 * 1000L
// A poll still unanswered after this long no longer blocks the next one.
@Field static final long POLL_STALE_MS = 2 * 20 * 1000L + 10000L
// Commands are gathered this long, then sent as one request per thermostat.
@Field static final long WRITE_GATHER_MS = 1500L
// After a change, read the thermostat back this many seconds later.
@Field static final int WRITE_CONFIRM_SECS = 5
// After a change couldn't be sent, read the thermostat back this much later.
@Field static final int RETRY_CONFIRM_SECS = 60
// After an outage polls pause for twice the poll interval, doubling up to this.
@Field static final long BACKOFF_MAX = 30 * 60 * 1000L
// Devices show healthStatus offline after this many failed polls in a row.
@Field static final int FAIL_LIMIT = 3
// ecobee status codes that mean the access token is no good.
@Field static final List AUTH_CODES = [1, 2, 14, 16]
// ecobee ends indefinite holds in 2035.
@Field static final String INDEFINITE_YEAR = "2035"

// In-memory per app: token-refresh waiters, pending writes, poll in flight.
@Field static final Map RUNTIME = new ConcurrentHashMap()

// ---------------------------------------------------------------- pages

def mainPage() {
    runPendingButton()
    expireLogin()
    initPicks()
    Map lg = state.login ?: [:]
    boolean working = loginWorking()
    boolean finding = discovering()
    // The page refreshes itself while signing in and until the devices exist.
    dynamicPage(name: "mainPage", title: "Ecobee Connect", install: true, uninstall: true, refreshInterval: (working || finding) ? 2 : 0) {
        if (!signedIn()) {
            section("Sign in to ecobee") {
                if (state.auth?.needsLogin) paragraph "<b>ecobee asked for a new sign-in.</b> Your devices stay in place and come back once you sign in."
                if (working) {
                    // While ecobee answers, show progress only; the form would look like a failed sign-in.
                    paragraph lg.mfaKind ? "Checking your code with ecobee…" : "Signing in to ecobee…"
                } else if (lg.step == "mfa") {
                    paragraph mfaPrompt(lg.mfaKind as String)
                    input "mfaCode", "text", title: "Verification code", required: false
                    input "btnMfa", "button", title: "Submit code"
                    input "btnCancel", "button", title: "Start over"
                } else {
                    paragraph "Use the email and password of your ecobee account. Hubitat keeps a sign-in token, not your password."
                    input "email", "text", title: "ecobee email", required: false
                    input "password", "password", title: "ecobee password", required: false
                    input "btnSignIn", "button", title: "Sign in"
                }
                if (lg.error) paragraph "<b>${lg.error}</b>"
            }
        } else {
            section("Account") {
                paragraph "Signed in as ${state.auth.account ?: 'your ecobee account'}. ${statusLine()}"
                input "btnSignOut", "button", title: "Sign out"
            }
            Map names = (state.names ?: [:]) as Map
            section("Thermostats") {
                if (names) {
                    input "thermostats", "enum", title: "Thermostats", options: pickerOptions(names), multiple: true, required: false, submitOnChange: true
                } else if (finding) {
                    paragraph "Looking for thermostats on your ecobee account…"
                } else {
                    paragraph "No thermostats found on this ecobee account.${state.lastError ? ' Last problem: ' + state.lastError : ''}"
                    input "btnFind", "button", title: "Look again"
                }
            }
            if (names) {
                section("Room sensors") {
                    Map opts = sensorOptions()
                    if (opts) input "sensors", "enum", title: "Room sensors", options: opts, multiple: true, required: false, submitOnChange: true
                    else if (!finding) paragraph "No room sensors on the picked thermostats."
                    if (isInstalled()) paragraph finding ? "Adding devices…" : devicesSummary()
                    else paragraph finding ? "Reading your thermostats…" : foundSummary()
                }
            }
            section("Behavior") {
                input "pollMinutes", "enum", title: "Check ecobee every", options: POLL_CHOICES, defaultValue: "3", required: true
                input "holdType", "enum", title: "Changes from Hubitat hold until",
                    options: ["nextTransition": "the next scheduled change", "indefinite": "you resume the schedule", "holdHours": "a number of hours"],
                    defaultValue: "nextTransition", required: true
                input "holdHours", "number", title: "Hours, when holding for a number of hours", range: "1..48", defaultValue: 2, required: false
                input "circulateMinutes", "number", title: "Fan minutes per hour for circulate", range: "5..55", defaultValue: 20, required: false
            }
            section("Logging") {
                input "logEnable", "bool", title: "Debug logging (turns itself off after 30 minutes)", defaultValue: false
            }
        }
    }
}

/**
 * True for a minute after sign-in (or Look again) until every picked thermostat
 * has been read, and once the app is installed, until each has its device.
 */
boolean discovering() {
    if (!signedIn() || now() >= ((state.discoverUntil ?: 0) as long)) return false
    if (!state.names) return true
    List ids = selectedIds()
    if (!ids) return false
    if (!isInstalled()) return ids.any { !((state.tstat ?: [:]) as Map)[it] }
    return ids.any { !getChildDevice(thermostatDni(it)) }
}

/**
 * Hubitat runs an app's timers only once it is installed (after its first
 * Done), so before that the app reads ecobee directly and adds devices on Done.
 */
boolean isInstalled() {
    try {
        return app.getInstallationState() == "COMPLETE"
    } catch (e) {
        return true
    }
}

/** Picker labels: each thermostat's name and how many room sensors it has, once known. */
Map pickerOptions(Map names) {
    Map tstats = (state.tstat ?: [:]) as Map
    names.collectEntries { id, name ->
        Map t = tstats[id] as Map
        int n = t ? roomSensors(t).size() : -1
        [(id): n < 0 ? name : "${name} (${plural(n, 'room sensor')})".toString()]
    }
}

List roomSensors(Map t) { ((t?.sensors ?: []).findAll { it.type != "thermostat" } as List).sort { it.name } }

String plural(int n, String word) { "${n} ${word}${n == 1 ? '' : 's'}" }

private String indent() { "&nbsp;&nbsp;&nbsp;&nbsp;" }

/** A room sensor's key in the sensor picker. */
String sensorKey(String tid, String sid) { "${tid}|${sid}" }

/** Every room sensor on the picked thermostats: key -> "Bedroom (on Main Floor)". */
Map sensorOptions() {
    Map tstats = (state.tstat ?: [:]) as Map
    Map out = [:]
    selectedIds().each { String id ->
        Map t = tstats[id] as Map
        roomSensors(t).each { Map rs -> out[sensorKey(id, rs.id as String)] = "${rs.name} (on ${t.name ?: id})".toString() }
    }
    return out
}

/**
 * Picked thermostat ids. Until the pickers are first filled in (state.picksSet),
 * every thermostat found counts as picked; after that, only checked ones.
 */
List<String> selectedIds() {
    if (settings.thermostats != null) return (settings.thermostats as List).collect { it as String }
    if (state.picksSet) return []
    return ((state.names ?: [:]) as Map).keySet().collect { it as String }
}

/** Picked room sensor keys; before the pickers are filled in, all of them (unless the old sensors switch was off). */
List<String> selectedSensorKeys() {
    if (settings.sensors != null) return (settings.sensors as List).collect { it as String }
    if (state.picksSet || settings.createSensors == false) return []
    return sensorOptions().keySet() as List
}

/**
 * Once every thermostat found has been read, check everything in the pickers
 * so they show what will be added. Runs once; an earlier install keeps its
 * thermostat picks, and its old "add room sensors" switch decides the sensors.
 */
void initPicks() {
    if (state.picksSet) return
    Map names = (state.names ?: [:]) as Map
    Map tstats = (state.tstat ?: [:]) as Map
    if (!names || names.keySet().any { !tstats[it] }) return
    List ids = selectedIds()
    List keys = selectedSensorKeys()
    if (settings.thermostats == null && ids) app.updateSetting("thermostats", [type: "enum", value: ids])
    if (settings.sensors == null && keys) app.updateSetting("sensors", [type: "enum", value: keys])
    app.removeSetting("createSensors")
    state.picksSet = true
}

/** Before the first Done: the picked thermostats and room sensors, and that Done adds them. */
String foundSummary() {
    Map tstats = (state.tstat ?: [:]) as Map
    Set keys = selectedSensorKeys() as Set
    List lines = []
    int nT = 0
    int nS = 0
    int skipped = 0
    selectedIds().each { String id ->
        Map t = tstats[id] as Map
        if (!t) return
        nT++
        def temp = t.runtime?.actualTemperature
        lines << "${t.name ?: id}: thermostat${temp != null ? ', ' + tempOf(temp as int) + tempUnit() : ''}"
        roomSensors(t).each { Map rs ->
            if (!(sensorKey(id, rs.id as String) in keys)) {
                skipped++
                return
            }
            nS++
            boolean known = rs.temp != null && rs.temp != "unknown"
            lines << "${indent()}${rs.name}: room sensor, ${known ? tempOf(rs.temp as int) + tempUnit() : 'no reading'}"
        }
    }
    if (!nT) return "No thermostats picked."
    String head = "<b>To add: ${plural(nT, 'thermostat')}${nS ? ' and ' + plural(nS, 'room sensor') : ''}</b>"
    String note = skipped ? "<br>${plural(skipped, 'room sensor')} not picked." : ""
    return head + "<br>" + lines.join("<br>") + note + "<br><br>Press <b>Done</b> to add ${nT + nS == 1 ? 'it' : 'them'} as devices."
}

void findThermostats() {
    state.discoverUntil = now() + DISCOVER_MS
    refreshNow()
}

/** The devices this app made, grouped by thermostat, labelled and linked to their pages. */
String devicesSummary() {
    List lines = []
    int nT = 0
    int nS = 0
    getChildDevices().findAll { it.deviceNetworkId.count(":") == 1 }.sort { it.displayName }.each { t ->
        nT++
        lines << "${deviceLink(t)}: thermostat${reading(t, ', ')}"
        getChildDevices().findAll { it.deviceNetworkId.startsWith(t.deviceNetworkId + ":") }.sort { it.displayName }.each { cd ->
            nS++
            lines << "${indent()}${deviceLink(cd)}: room sensor${reading(cd, ', ') ?: ', no reading'}"
        }
    }
    String pending = changesPending() ? "<br><br>Press <b>Done</b> to apply your changes." : ""
    if (!nT) return "No devices yet." + pending
    return "<b>Devices: ${plural(nT, 'thermostat')}${nS ? ', ' + plural(nS, 'room sensor') : ''}</b><br>" + lines.join("<br>") + pending
}

/** Whether the picks differ from the devices that exist. */
boolean changesPending() {
    Map tstats = (state.tstat ?: [:]) as Map
    Set keys = selectedSensorKeys() as Set
    Set want = [] as Set
    selectedIds().each { String id ->
        want << thermostatDni(id)
        roomSensors(tstats[id] as Map).each { Map rs ->
            if (sensorKey(id, rs.id as String) in keys) want << sensorDni(id, rs.id as String)
        }
    }
    return want != (getChildDevices()*.deviceNetworkId as Set)
}

private String deviceLink(cd) { "<a href='/device/edit/${cd.id}' target='_blank'>${cd.displayName}</a>" }

private String reading(cd, String sep) {
    def temp = cd.currentValue("temperature")
    return temp != null ? "${sep}${temp}${tempUnit()}" : ""
}

void runPendingButton() {
    String btn = state.pendingButton
    if (!btn) return
    state.pendingButton = null
    if (btn == "btnSignIn") startLogin()
    else if (btn == "btnMfa") submitMfa()
}

String mfaPrompt(String kind) {
    if (kind == "sms") return "ecobee sent a code to the phone number on your account. Enter it below."
    if (kind == "voice") return "ecobee is calling the phone number on your account with a code. Enter it below."
    return "Enter the 6-digit code from your authenticator app."
}

String statusLine() {
    if (state.lastError) return "Last problem: ${state.lastError}"
    if (state.lastPoll) return "Last update ${new Date(state.lastPoll as long).format('MMM d HH:mm', location.timeZone)}."
    return ""
}

/**
 * Hubitat calls this before it saves what was just typed into the page, so the
 * buttons that read inputs only take note; the page runs them when it redraws.
 * Those inputs must not use submitOnChange: leaving the field would redraw the
 * page under the click and the button would need a second press.
 */
void appButtonHandler(String btn) {
    switch (btn) {
        case "btnSignIn":
        case "btnMfa":
            state.pendingButton = btn
            break
        case "btnCancel": state.login = null; app.removeSetting("mfaCode"); break
        case "btnSignOut": signOut(); break
        case "btnFind": findThermostats(); break
    }
}

// ---------------------------------------------------------------- lifecycle

def installed() {
    initialize()
}

def updated() {
    initialize()
}

def uninstalled() {
    unschedule()
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
    RUNTIME.remove(app.id)
}

void initialize() {
    unschedule()
    if (settings.logEnable) runIn(1800, "logsOff")
    if (!signedIn()) return
    int mins = ((settings.pollMinutes ?: "3") as int)
    // A random second and minute offset spreads hubs across ecobee's minute.
    int sec = new Random().nextInt(60)
    int off = new Random().nextInt(mins)
    schedule("${sec} ${off}/${mins} * ? * *", "poll")
    syncChildren()
    state.forceFull = true
    poll()
}

def logsOff() {
    log.warn "Ecobee Connect: debug logging turned off"
    app.updateSetting("logEnable", [value: "false", type: "bool"])
}

boolean signedIn() { state.auth?.refresh && !state.auth?.needsLogin }

/** Another ecobee account signed in: its thermostats start fresh and the old account's devices go on Done. */
private void forgetAccount() {
    log.info "Ecobee Connect: signed in to a different ecobee account; thermostat and sensor picks start over"
    ["names", "tstat", "revs", "picksSet"].each { state.remove(it) }
    app.removeSetting("thermostats")
    app.removeSetting("sensors")
}

void signOut() {
    unschedule()
    state.auth = null
    state.login = null
    state.revs = [:]
    runtime().clear()
    markAllHealth("offline")
    log.info "Ecobee Connect: signed out"
}

private Map runtime() {
    Map rt = RUNTIME[app.id]
    if (rt == null) {
        rt = new ConcurrentHashMap()
        RUNTIME[app.id] = rt
    }
    return rt
}

// ---------------------------------------------------------------- sign-in

boolean loginWorking() {
    Map lg = state.login
    return lg?.step == "working" && now() - ((lg.at ?: 0) as long) < LOGIN_TIMEOUT_MS
}

/** A sign-in that stopped getting answers: say so instead of quietly showing the form again. */
void expireLogin() {
    Map lg = state.login
    if (lg?.step == "working" && !loginWorking()) loginFail("ecobee didn't answer the sign-in in time. Try again.")
}

void startLogin() {
    String email = (settings.email ?: "").toString().trim()
    if (!email || !settings.password) {
        state.login = [step: "idle", error: "Enter your ecobee email and password."]
        return
    }
    String verifier = randomToken() + randomToken()
    state.login = [id: randomToken(), step: "working", at: now(), hops: 0, verifier: verifier, cookies: [:], email: email]
    loginRequest("authorize", "GET", "/authorize", [
        response_type: "code",
        client_id: CLIENT_ID,
        redirect_uri: REDIRECT_URI,
        audience: AUDIENCE,
        scope: SCOPE,
        state: randomToken(),
        code_challenge: pkceChallenge(verifier),
        code_challenge_method: "S256",
        prompt: "login"
    ])
}

void submitMfa() {
    Map lg = state.login
    String code = (settings.mfaCode ?: "").toString().replaceAll(/\s/, "")
    if (lg?.step != "mfa") return
    if (!(code ==~ /\d{4,10}/)) {
        lg.error = "Enter the code ecobee sent you, digits only."
        state.login = lg
        return
    }
    app.removeSetting("mfaCode")
    lg.step = "working"
    lg.at = now()
    lg.error = null
    state.login = lg
    loginRequest("mfa", "POST", lg.mfaPath as String, [state: lg.mfaState], [state: lg.mfaState, code: code, action: "default"])
}

/** One step of the sign-in on auth.ecobee.com; redirects are followed by hand so cookies and the code can be read. */
private void loginRequest(String step, String method, String path, Map query, Map form = null) {
    Map lg = state.login
    Map headers = ["User-Agent": BROWSER_UA, "Accept": "text/html,application/xhtml+xml,*/*", "Accept-Language": "en-US,en;q=0.9"]
    String cookie = ((lg.cookies ?: [:]) as Map).collect { k, v -> "${k}=${v}" }.join("; ")
    if (cookie) headers["Cookie"] = cookie
    Map params = [uri: AUTH_HOST, path: path, query: query ?: [:], headers: headers, timeout: REQUEST_TIMEOUT, followRedirects: false, textParser: true]
    if (method == "POST") {
        headers["Origin"] = AUTH_HOST
        headers["Referer"] = AUTH_HOST + path + (query?.state ? "?state=${query.state}" : "")
        params.requestContentType = "application/x-www-form-urlencoded"
        params.body = formEncode(form)
        asynchttpPost("loginResp", params, [id: lg.id, step: step])
    } else {
        asynchttpGet("loginResp", params, [id: lg.id, step: step])
    }
}

void loginResp(resp, Map data) {
    Map lg = state.login
    if (!lg || lg.id != data?.id || lg.step != "working") return
    int status = statusOf(resp)
    mergeCookies(lg, resp)
    String loc = headerOf(resp, "Location")
    logDebug "Sign-in ${data.step}: HTTP ${status}${loc ? ' to ' + splitUrl(loc).path : ''}; cookies ${((lg.cookies ?: [:]) as Map).keySet()}"
    if (data.step == "token") {
        tokenResp(lg, status, bodyOf(resp))
        return
    }
    if (status in [301, 302, 303, 307, 308] && loc) {
        // A redirect after the password step means it was accepted; it isn't needed again.
        if (data.step == "password") app.removeSetting("password")
        state.login = lg
        followLogin(loc)
        return
    }
    String body = bodyOf(resp)
    String code = errorCodeIn(body)
    switch (data.step) {
        case "challenge":
            // The code page itself (loading it is what sends a text message).
            if (status == 200) {
                lg.step = "mfa"
                state.login = lg
            } else {
                loginFail("ecobee's verification page answered HTTP ${status}.")
            }
            break
        case "identifier":
            loginFail("ecobee didn't accept that email${code ? ' (' + code + ')' : ''}.")
            break
        case "password":
            if (code && code.toLowerCase() =~ /block|lock|too-many|suspend/) loginFail("ecobee has locked sign-ins for this account for now (${code}). Try again later or sign in at ecobee.com.")
            else loginFail("ecobee didn't accept that password.")
            break
        case "mfa":
            lg.step = "mfa"
            lg.error = (body?.toLowerCase()?.contains("expired")) ? "That code has expired. Start over to get a new one." : "That code wasn't accepted. Check it and try again."
            state.login = lg
            break
        case "prompt":
            loginFail("ecobee is asking for a sign-in step this app can't complete (${lg.promptPath}). Sign in at ecobee.com in a browser, finish what it asks, then try again.")
            break
        default:
            loginFail("ecobee's sign-in answered HTTP ${status} at the ${data.step} step.")
    }
}

/** Decide the next sign-in step from where Auth0 redirected. */
void followLogin(String loc) {
    Map lg = state.login
    lg.hops = ((lg.hops ?: 0) as int) + 1
    state.login = lg
    if (lg.hops > LOGIN_MAX_HOPS) {
        loginFail("ecobee kept asking for more sign-in steps. Sign in at ecobee.com in a browser, finish what it asks, then try again.")
        return
    }
    if (loc.startsWith(REDIRECT_URI)) {
        Map q = splitUrl(loc).query
        if (q.error) {
            loginFail("ecobee refused the sign-in: ${q.error_description ?: q.error}")
        } else if (q.code) {
            loginRequest("token", "POST", "/oauth/token", [:], [grant_type: "authorization_code", client_id: CLIENT_ID,
                code: q.code, code_verifier: lg.verifier, redirect_uri: REDIRECT_URI])
        } else {
            loginFail("ecobee finished the sign-in without a code.")
        }
        return
    }
    Map u = splitUrl(loc)
    if (u.host && u.host != AUTH_HOST) {
        loginFail("ecobee's sign-in sent us somewhere unexpected (${u.host}).")
        return
    }
    String path = u.path
    String st = u.query.state
    if (path == "/u/login" || path == "/u/login/password") {
        // /u/login has the email and password on one page.
        loginRequest("password", "POST", path, [state: st], [state: st, username: lg.email, password: settings.password ?: "", action: "default"])
    } else if (path == "/u/login/identifier") {
        if (lg.identified) {
            loginFail("ecobee didn't recognise that email.")
        } else {
            lg.identified = true
            state.login = lg
            loginRequest("identifier", "POST", path, [state: st], [state: st, username: lg.email, "js-available": "true",
                "webauthn-available": "true", "is-brave": "false", "webauthn-platform-available": "false", action: "default"])
        }
    } else if (path == "/authorize/resume" || path == "/authorize") {
        loginRequest("resume", "GET", path, u.query)
    } else if (path ==~ /\/u\/mfa-(otp|sms|voice)-challenge.*/) {
        lg.mfaPath = path
        lg.mfaState = st
        lg.mfaKind = (path =~ /mfa-(otp|sms|voice)/)[0][1]
        state.login = lg
        loginRequest("challenge", "GET", path, [state: st])
    } else if (path.startsWith("/u/mfa")) {
        loginFail("ecobee asked for a verification method this app can't use (${path}). In the ecobee app, switch two-step verification to an authenticator app or text message, then try again.")
    } else if (path.startsWith("/u/")) {
        // Prompts that only need their main button (terms, notices).
        lg.promptPath = path
        state.login = lg
        loginRequest("prompt", "POST", path, [state: st], [state: st, action: "default"])
    } else {
        loginFail("ecobee's sign-in went somewhere unexpected (${path}).")
    }
}

private void tokenResp(Map lg, int status, String body) {
    Map json = parseJson(body)
    if (status != 200 || !json?.access_token) {
        loginFail("ecobee didn't hand out a sign-in token (HTTP ${status}${json?.error ? ', ' + json.error : ''}).")
        return
    }
    if (!json.refresh_token) {
        loginFail("ecobee signed in but gave no refresh token, so the sign-in would end within the hour.")
        return
    }
    String before = state.account as String
    if (before && !before.equalsIgnoreCase(lg.email as String)) forgetAccount()
    state.account = lg.email
    state.auth = [refresh: json.refresh_token, access: json.access_token, expires: now() + ((json.expires_in ?: 3600) as long) * 1000L, account: lg.email]
    state.login = [step: "done"]
    state.lastError = null
    app.removeSetting("password")
    app.removeSetting("mfaCode")
    state.discoverUntil = now() + DISCOVER_MS
    log.info "Ecobee Connect: signed in as ${lg.email}"
    restoreHealth()
    if (isInstalled()) {
        initialize()
    } else {
        state.forceFull = true
        poll()
    }
}

private void loginFail(String msg) {
    log.warn "Ecobee Connect: sign-in failed: ${msg}"
    state.login = [step: "idle", error: msg]
    app.removeSetting("password")
    app.removeSetting("mfaCode")
}

// ---------------------------------------------------------------- tokens

/** Run `action(args)` with a fresh access token, refreshing it first when needed. */
void withToken(String action, Map args) {
    Map a = state.auth
    if (!a?.refresh || a.needsLogin) return
    if (a.access && now() < ((a.expires ?: 0) as long) - TOKEN_MARGIN_MS) {
        "${action}"(args)
        return
    }
    Map rt = runtime()
    List waiters = (rt.waiters ?: []) as List
    waiters << [action: action, args: args]
    rt.waiters = waiters
    if (rt.refreshing && now() - ((rt.refreshAt ?: 0) as long) < REFRESH_STALE_MS) return
    rt.refreshing = true
    rt.refreshAt = now()
    asynchttpPost("refreshResp", [uri: AUTH_HOST, path: "/oauth/token", requestContentType: "application/x-www-form-urlencoded",
        contentType: "application/json", timeout: REQUEST_TIMEOUT, textParser: true,
        body: formEncode([grant_type: "refresh_token", client_id: CLIENT_ID, refresh_token: a.refresh])])
}

void refreshResp(resp, Map data) {
    Map rt = runtime()
    rt.refreshing = false
    List waiters = (rt.waiters ?: []) as List
    rt.waiters = []
    int status = statusOf(resp)
    Map json = parseJson(bodyOf(resp))
    Map a = state.auth
    if (!a) return
    if (status == 200 && json?.access_token) {
        a.access = json.access_token
        a.expires = now() + ((json.expires_in ?: 3600) as long) * 1000L
        // Auth0 may rotate the refresh token; the old one stops working soon after.
        if (json.refresh_token) a.refresh = json.refresh_token
        state.auth = a
        logDebug "Access token renewed"
        waiters.each { Map w -> "${w.action}"(w.args) }
        return
    }
    if (status in [400, 401, 403] && json?.error in ["invalid_grant", "unauthorized_client", "access_denied"]) {
        needLogin("ecobee ended the sign-in (${json.error_description ?: json.error}).")
        return
    }
    pollFailed("token renewal answered HTTP ${status}", status)
    // Changes that were waiting on the renewal weren't sent: say so, and read
    // those thermostats back later so the devices drop the values shown early.
    waiters.findAll { it.action == "sendWrite" }.each { Map w ->
        log.warn "Ecobee Connect: a change wasn't sent because the sign-in couldn't be renewed; try it again"
        confirmLater(w.args.id as String, RETRY_CONFIRM_SECS)
    }
}

private void needLogin(String why) {
    Map a = state.auth ?: [:]
    a.needsLogin = true
    a.access = null
    state.auth = a
    state.lastError = why
    unschedule("poll")
    log.warn "Ecobee Connect: ${why} Open the app and sign in again."
    markAllHealth("offline")
}

/**
 * An ecobee answer that says the access token is no good: renew it and retry
 * once. A second rejection is handled by the caller as an ordinary failure
 * (with backoff); only Auth0 refusing the refresh token ends the sign-in, since
 * ecobee also answers status 1 when its own servers are struggling.
 */
private boolean retriedAuth(Map ans, String action, Map args) {
    if (!ans.auth || args?.retried) return false
    Map a = state.auth
    if (a) {
        a.expires = 0L
        state.auth = a
    }
    withToken(action, (args ?: [:]) + [retried: true])
    return true
}

// ---------------------------------------------------------------- polling

/** Scheduled poll. */
void poll() {
    if (!signedIn()) return
    if (now() < ((state.backoffUntil ?: 0) as long)) {
        logDebug "Poll skipped: backing off after errors"
        return
    }
    Map rt = runtime()
    if (rt.polling && now() - ((rt.pollAt ?: 0) as long) < POLL_STALE_MS) return
    rt.polling = true
    rt.pollAt = now()
    withToken("sendSummary", [:])
}

/** From a device's Refresh: read everything now. */
void refreshNow() {
    state.forceFull = true
    poll()
}

void sendSummary(Map args) {
    asynchttpGet("summaryResp", apiGetParams("/1/thermostatSummary",
        [selection: [selectionType: "registered", selectionMatch: "", includeEquipmentStatus: true]]), args)
}

void summaryResp(resp, Map data) {
    Map ans = apiAnswer(resp)
    if (retriedAuth(ans, "sendSummary", data)) return
    if (!ans.ok) {
        runtime().polling = false
        pollFailed("summary: ${ans.msg}", ans.http as int)
        return
    }
    Map sum = parseSummary(ans.json)
    state.names = sum.collectEntries { id, s -> [(id): s.name ?: id] }
    List ids = selectedIds().findAll { sum.containsKey(it) }
    Map revs = (state.revs ?: [:]) as Map
    Map tstats = (state.tstat ?: [:]) as Map
    boolean force = state.forceFull as boolean
    List full = []
    List rtOnly = []
    ids.each { String id ->
        Map s = sum[id]
        Map old = revs[id] as Map
        if (force || !old || old.t != s.thermostatRev || !tstats[id]) full << id
        else if (old.r != s.runtimeRev) rtOnly << id
        // Equipment status and connection come with the summary: apply them now.
        Map t = tstats[id] as Map
        if (t) {
            t.equipment = s.equipment
            t.connected = s.connected
            applyThermostat(id, t, ["thermostatOperatingState", "equipmentStatus", "thermostatSetpoint", "connected", "healthStatus"])
        }
    }
    state.tstat = tstats
    if (!full && !rtOnly) {
        state.forceFull = false
        pollOk()
        logDebug "No changes at ecobee"
        return
    }
    Map fetchArgs = [full: full, runtime: rtOnly, revs: (full + rtOnly).collectEntries { [(it): [t: sum[it].thermostatRev, r: sum[it].runtimeRev]] },
        equipment: (full + rtOnly).collectEntries { [(it): sum[it].equipment] }, forced: force]
    sendFetch(fetchArgs)
}

void sendFetch(Map args) {
    List ids = ((args.full ?: []) + (args.runtime ?: [])) as List
    boolean full = args.full as boolean
    Map sel = [selectionType: "thermostats", selectionMatch: ids.join(","), includeRuntime: true, includeSensors: true]
    if (full) sel += [includeSettings: true, includeProgram: true, includeEvents: true]
    logDebug "Fetching ${full ? 'settings, program, events, ' : ''}runtime and sensors for ${ids}"
    asynchttpGet("fetchResp", apiGetParams("/1/thermostat", [selection: sel]), args)
}

void fetchResp(resp, Map data) {
    Map ans = apiAnswer(resp)
    if (retriedAuth(ans, "sendFetch", data)) return
    runtime().polling = false
    if (!ans.ok) {
        pollFailed("thermostat: ${ans.msg}", ans.http as int)
        return
    }
    Map tstats = (state.tstat ?: [:]) as Map
    Map revs = (state.revs ?: [:]) as Map
    List fetched = []
    (ans.json?.thermostatList ?: []).each { Map raw ->
        String id = raw.identifier as String
        fetched << id
        Map t = mergeThermostat((tstats[id] ?: [:]) as Map, raw)
        if (data.equipment?.containsKey(id)) t.equipment = data.equipment[id]
        tstats[id] = t
        Map r = (data.revs?.get(id) ?: [:]) as Map
        revs[id] = [t: raw.thermostatRev ?: r.t ?: revs[id]?.t, r: raw.runtime?.runtimeRev ?: r.r]
    }
    state.tstat = tstats
    state.revs = revs
    // A device Refresh stays requested until a forced fetch has succeeded.
    if (data.forced) state.forceFull = false
    pollOk()
    if (isInstalled()) syncChildren()
    List picked = selectedIds()
    fetched.findAll { it in picked }.each { String id -> applyAll(id, tstats[id] as Map) }
}

private void pollOk() {
    runtime().polling = false
    state.lastPoll = now()
    state.lastError = null
    boolean recovering = state.fails as boolean
    state.fails = 0
    state.backoffUntil = 0L
    if (recovering) restoreHealth()
}

private void pollFailed(String msg, int http) {
    runtime().polling = false
    int fails = ((state.fails ?: 0) as int) + 1
    state.fails = fails
    state.lastError = msg
    long pollMs = ((settings.pollMinutes ?: "3") as long) * 60000L
    if (http == 429 || http >= 500 || http <= 0) {
        long wait = Math.min(BACKOFF_MAX, pollMs * (1L << Math.min(fails, 5)))
        state.backoffUntil = now() + wait
        log.warn "Ecobee Connect: ${msg}; next try in ${(wait / 60000) as int} min"
    } else {
        log.warn "Ecobee Connect: ${msg}"
    }
    if (fails >= FAIL_LIMIT) markAllHealth("offline")
}

// ---------------------------------------------------------------- commands from devices

void setMode(String dni, String mode) {
    Map t = thermostatFor(dni)
    if (!t) return
    String api = MODE_TO_API[mode]
    if (!api || !(mode in supportedModes(t.settings as Map))) {
        log.warn "Ecobee Connect: ${t.name} doesn't support mode ${mode}"
        return
    }
    queueWrite(t.id as String, [mode: api])
    optimistic(t.id as String, [[name: "thermostatMode", value: mode]])
}

void setFan(String dni, String fan) {
    Map t = thermostatFor(dni)
    if (!t) return
    switch (fan) {
        case "on":
            queueWrite(t.id as String, [fan: "on"])
            break
        case "auto":
            Map ch = [:]
            if (t.runtime?.desiredFanMode == "on") ch.fan = "auto"
            if (((t.settings?.fanMinOnTime ?: 0) as int) > 0) ch.fanMin = 0
            if (ch) queueWrite(t.id as String, ch)
            break
        case "circulate":
            Map ch = [fanMin: Math.max(5, Math.min(55, (settings.circulateMinutes ?: 20) as int))]
            if (t.runtime?.desiredFanMode == "on") ch.fan = "auto"
            queueWrite(t.id as String, ch)
            break
        default:
            log.warn "Ecobee Connect: unknown fan mode ${fan}"
            return
    }
    optimistic(t.id as String, [[name: "thermostatFanMode", value: fan]])
}

void setFanMin(String dni, Number minutes) {
    Map t = thermostatFor(dni)
    if (!t) return
    int m = Math.max(0, Math.min(55, minutes as int))
    queueWrite(t.id as String, [fanMin: m])
    optimistic(t.id as String, [[name: "fanMinOnTime", value: m]])
}

void setHeat(String dni, Number temp) { setPoint(dni, "heat", temp) }

void setCool(String dni, Number temp) { setPoint(dni, "cool", temp) }

private void setPoint(String dni, String which, Number temp) {
    Map t = thermostatFor(dni)
    if (!t) return
    queueWrite(t.id as String, [(which): toF10(temp)])
    optimistic(t.id as String, [[name: which == "heat" ? "heatingSetpoint" : "coolingSetpoint", value: setpointOf(toF10(temp)), unit: tempUnit()]])
}

/** A comfort profile by name or climateRef; "schedule" or "resume" resumes the program. */
void setClimate(String dni, String name) {
    Map t = thermostatFor(dni)
    if (!t) return
    String n = (name ?: "").trim()
    if (n.toLowerCase() in ["schedule", "resume", "program"]) {
        resume(dni)
        return
    }
    Map c = ((t.climates ?: []) as List).find { it.ref == n || it.name?.equalsIgnoreCase(n) } as Map
    if (!c) {
        log.warn "Ecobee Connect: ${t.name} has no comfort profile named ${n}; it has ${(t.climates ?: [])*.name}"
        return
    }
    queueWrite(t.id as String, [climate: c.ref])
    optimistic(t.id as String, [[name: "currentClimate", value: c.name], [name: "holdStatus", value: "hold"]])
}

void resume(String dni) {
    Map t = thermostatFor(dni)
    if (!t) return
    queueWrite(t.id as String, [resume: true])
    optimistic(t.id as String, [[name: "holdStatus", value: "schedule"], [name: "holdUntil", value: "none"]])
}

/** A map that lives as long as the app's runtime entry and is never replaced, so concurrent executions share it. */
private Map sharedMap(String name) {
    Map rt = runtime()
    rt.putIfAbsent(name, new ConcurrentHashMap())
    return rt[name] as Map
}

/** Gather changes per thermostat; later ones replace earlier ones of the same kind. */
private void queueWrite(String id, Map change) {
    Map pending = sharedMap("pending")
    // Compare-and-set, so a command arriving while another is merged or flushed isn't lost.
    while (true) {
        Map old = pending[id] as Map
        Map p = new LinkedHashMap(old ?: [:])
        if (change.resume) p.keySet().removeAll(["climate", "heat", "cool", "fan"])
        if (change.climate) p.keySet().removeAll(["heat", "cool", "fan"])
        if (change.heat != null || change.cool != null || change.fan) p.remove("climate")
        p.putAll(change)
        if (old == null ? pending.putIfAbsent(id, p) == null : pending.replace(id, old, p)) break
    }
    runInMillis(WRITE_GATHER_MS, "flushWrites")
}

void flushWrites() {
    Map pending = sharedMap("pending")
    new ArrayList(pending.keySet()).each { String id ->
        Map p = pending.remove(id) as Map
        if (!p) return
        Map body = writeBody(id, p, (state.tstat ?: [:])[id] as Map)
        if (body) withToken("sendWrite", [id: id, body: body])
    }
}

/** The request body for gathered changes `p`, given the cached thermostat `t`. */
Map writeBody(String id, Map p, Map t) {
    Map body = [selection: [selectionType: "thermostats", selectionMatch: id]]
    Map s = [:]
    if (p.mode) s.hvacMode = p.mode
    if (p.fanMin != null) s.fanMinOnTime = p.fanMin
    if (s) body.thermostat = [settings: s]
    List fns = []
    if (p.resume) fns << [type: "resumeProgram", params: [resumeAll: true]]
    if (p.climate) {
        fns << [type: "setHold", params: holdParams() + [holdClimateRef: p.climate]]
    } else if (p.heat != null || p.cool != null || p.fan) {
        Integer heat = (p.heat ?: t?.runtime?.desiredHeat) as Integer
        Integer cool = (p.cool ?: t?.runtime?.desiredCool) as Integer
        int delta = ((t?.settings?.heatCoolMinDelta ?: 50) as int)
        if (heat != null && cool != null) {
            if (p.heat != null && cool - heat < delta) cool = heat + delta
            else if (p.cool != null && cool - heat < delta) heat = cool - delta
        }
        if (heat == null || cool == null) {
            log.warn "Ecobee Connect: setpoints of ${t?.name ?: id} aren't known yet; try again after the next update"
        } else {
            Map params = holdParams() + [heatHoldTemp: heat, coolHoldTemp: cool]
            if (p.fan) params.fan = p.fan
            fns << [type: "setHold", params: params]
        }
    }
    if (fns) body.functions = fns
    return (body.thermostat || body.functions) ? body : null
}

Map holdParams() {
    String type = settings.holdType ?: "nextTransition"
    Map m = [holdType: type]
    if (type == "holdHours") m.holdHours = Math.max(1, Math.min(48, (settings.holdHours ?: 2) as int))
    return m
}

void sendWrite(Map args) {
    logDebug "Sending ${JsonOutput.toJson(args.body)}"
    asynchttpPost("writeResp", [uri: API_HOST, path: "/1/thermostat", query: [format: "json"], headers: apiHeaders(),
        requestContentType: "application/json", contentType: "application/json", timeout: REQUEST_TIMEOUT, textParser: true,
        body: JsonOutput.toJson(args.body)], args)
}

void writeResp(resp, Map data) {
    Map ans = apiAnswer(resp)
    if (retriedAuth(ans, "sendWrite", data)) return
    if (!ans.ok) log.warn "Ecobee Connect: ecobee didn't accept the change: ${ans.msg}"
    // Read back either way: on success to confirm, on failure to undo the optimistic values.
    confirmLater(data.id as String, WRITE_CONFIRM_SECS)
}

/** Read this thermostat back after `secs`, together with any others waiting. */
private void confirmLater(String id, int secs) {
    sharedMap("confirm")[id] = true
    runIn(secs, "confirmWrites")
}

void confirmWrites() {
    Map confirm = sharedMap("confirm")
    List ids = new ArrayList(confirm.keySet()).findAll { confirm.remove(it) != null }
    if (ids) withToken("sendFetch", [full: ids, runtime: []])
}

// ---------------------------------------------------------------- child devices

String thermostatDni(String id) { "ecobee:${id}" }

String sensorDni(String id, String sensorId) { "ecobee:${id}:${sensorId.replaceAll(/[^A-Za-z0-9]/, '-')}" }

void syncChildren() {
    List ids = selectedIds()
    Set keys = selectedSensorKeys() as Set
    Map tstats = (state.tstat ?: [:]) as Map
    ids.each { String id ->
        String dni = thermostatDni(id)
        if (!getChildDevice(dni)) {
            String label = ((state.names ?: [:]) as Map)[id] ?: "ecobee ${id}"
            try {
                addChildDevice("ecobeeconnect", THERMOSTAT_DRIVER, dni, [name: "ecobee Thermostat", label: label, isComponent: false])
                log.info "Ecobee Connect: added thermostat ${label}"
            } catch (e) {
                log.error "Ecobee Connect: couldn't add ${label}: ${e.message}. Is the ${THERMOSTAT_DRIVER} driver installed?"
            }
        }
        roomSensors(tstats[id] as Map).findAll { sensorKey(id, it.id as String) in keys }.each { Map s ->
            String sdni = sensorDni(id, s.id as String)
            if (!getChildDevice(sdni)) {
                try {
                    addChildDevice("ecobeeconnect", SENSOR_DRIVER, sdni, [name: "ecobee Sensor", label: s.name ?: s.id, isComponent: false])
                    log.info "Ecobee Connect: added sensor ${s.name}"
                } catch (e) {
                    log.error "Ecobee Connect: couldn't add sensor ${s.name}: ${e.message}. Is the ${SENSOR_DRIVER} driver installed?"
                }
            }
        }
    }
    removeUnpicked(ids, keys, tstats)
}

/**
 * Delete only devices the user unchecked. Until the pickers have been filled
 * in, nothing is deleted (the picks then follow whatever ecobee last listed),
 * and a sensor ecobee didn't list this time stays.
 */
private void removeUnpicked(List ids, Set keys, Map tstats) {
    if (!state.picksSet) return
    Map sensorKeysByDni = [:]
    tstats.each { String tid, Map t -> roomSensors(t).each { Map rs -> sensorKeysByDni[sensorDni(tid, rs.id as String)] = sensorKey(tid, rs.id as String) } }
    getChildDevices().each { cd ->
        String dni = cd.deviceNetworkId
        String tid = dni.split(":")[1]
        boolean isSensor = dni.count(":") >= 2
        boolean drop = !(tid in ids) || (isSensor && sensorKeysByDni[dni] && !(sensorKeysByDni[dni] in keys))
        if (drop) {
            log.info "Ecobee Connect: removing ${cd.displayName}"
            deleteChildDevice(dni)
        }
    }
}

private Map thermostatFor(String dni) {
    String id = dni?.split(":")?.getAt(1)
    Map t = ((state.tstat ?: [:]) as Map)[id] as Map
    if (!t) {
        log.warn "Ecobee Connect: no data yet for ${dni}; try again after the next update"
        return null
    }
    return t + [id: id]
}

private void applyAll(String id, Map t) {
    applyThermostat(id, t, null)
    (t.sensors ?: []).findAll { it.type != "thermostat" }.each { Map s ->
        def cd = getChildDevice(sensorDni(id, s.id as String))
        if (cd) cd.applyEvents(sensorEvents(s))
    }
}

/** Send the thermostat's events, or only the named ones. */
private void applyThermostat(String id, Map t, List only) {
    def cd = getChildDevice(thermostatDni(id))
    if (!cd) return
    List evts = thermostatEvents(t)
    if (only) evts = evts.findAll { it.name in only }
    cd.applyEvents(evts)
}

private void optimistic(String id, List evts) {
    def cd = getChildDevice(thermostatDni(id))
    if (cd) cd.applyEvents(evts)
}

/**
 * After an outage or a new sign-in: each device's health from its last known
 * data (a disconnected thermostat or a sensor without a reading stays offline),
 * and online for devices with no data yet.
 */
private void restoreHealth() {
    Map tstats = (state.tstat ?: [:]) as Map
    getChildDevices().each { cd ->
        List parts = cd.deviceNetworkId.split(":") as List
        Map t = tstats[parts[1]] as Map
        String health = "online"
        if (t && parts.size() == 2) {
            health = t.connected == false ? "offline" : "online"
        } else if (t) {
            Map rs = roomSensors(t).find { sensorDni(parts[1], it.id as String) == cd.deviceNetworkId } as Map
            if (rs) health = sensorEvents(rs).find { it.name == "healthStatus" }.value
        }
        cd.applyEvents([[name: "healthStatus", value: health]])
    }
}

private void markAllHealth(String v) {
    getChildDevices().each { it.applyEvents([[name: "healthStatus", value: v]]) }
}

// ---------------------------------------------------------------- ecobee mapping

@Field static final Map MODE_FROM_API = [heat: "heat", cool: "cool", auto: "auto", off: "off", auxHeatOnly: "emergency heat"]
@Field static final Map MODE_TO_API = [heat: "heat", cool: "cool", auto: "auto", off: "off", "emergency heat": "auxHeatOnly"]

/** id -> [name, connected, thermostatRev, runtimeRev, equipment] from a thermostatSummary answer. */
Map parseSummary(Map json) {
    Map out = [:]
    (json?.revisionList ?: []).each { String line ->
        List f = line.split(":", -1) as List
        if (f.size() < 7) return
        out[f[0]] = [name: f[1], connected: f[2] == "true", thermostatRev: f[3], alertsRev: f[4], runtimeRev: f[5], intervalRev: f[6], equipment: ""]
    }
    (json?.statusList ?: []).each { String line ->
        int i = line.indexOf(":")
        if (i > 0 && out[line.take(i)]) out[line.take(i)].equipment = line.substring(i + 1)
    }
    return out
}

/** Keep only what the mapping needs, merged over the cached copy (a runtime-only fetch has no settings). */
Map mergeThermostat(Map old, Map raw) {
    Map t = new LinkedHashMap(old)
    t.name = raw.name ?: t.name
    if (raw.runtime) {
        Map r = raw.runtime
        t.runtime = [actualTemperature: r.actualTemperature, actualHumidity: r.actualHumidity, desiredHeat: r.desiredHeat,
            desiredCool: r.desiredCool, desiredFanMode: r.desiredFanMode, connected: r.connected]
        if (r.connected != null) t.connected = r.connected
    }
    if (raw.settings) {
        Map s = raw.settings
        t.settings = [hvacMode: s.hvacMode, heatCoolMinDelta: s.heatCoolMinDelta, hasHeatPump: s.hasHeatPump,
            heatStages: s.heatStages, coolStages: s.coolStages, autoHeatCoolFeatureEnabled: s.autoHeatCoolFeatureEnabled,
            fanMinOnTime: s.fanMinOnTime]
    }
    if (raw.program) {
        t.climates = (raw.program.climates ?: []).collect { [name: it.name, ref: it.climateRef] }
        t.currentClimateRef = raw.program.currentClimateRef
    }
    if (raw.events != null) {
        Map e = (raw.events ?: []).find { it.running } as Map
        t.event = e ? [type: e.type, climate: e.holdClimateRef, endDate: e.endDate, endTime: e.endTime] : null
    }
    if (raw.remoteSensors != null) {
        t.sensors = (raw.remoteSensors ?: []).collect { Map s ->
            Map caps = (s.capability ?: []).collectEntries { [(it.type): it.value] }
            [id: s.id, name: s.name, type: s.type, inUse: s.inUse, temp: caps.temperature, occ: caps.occupancy, hum: caps.humidity]
        }
    }
    return t
}

List supportedModes(Map s) {
    if (!s) return ["off"]
    boolean hp = s.hasHeatPump as boolean
    boolean heat = hp || ((s.heatStages ?: 0) as int) > 0
    boolean cool = hp || ((s.coolStages ?: 0) as int) > 0
    List m = []
    if (s.autoHeatCoolFeatureEnabled && heat && cool) m << "auto"
    if (cool) m << "cool"
    if (hp && ((s.heatStages ?: 0) as int) > 0) m << "emergency heat"
    if (heat) m << "heat"
    m << "off"
    String cur = MODE_FROM_API[s.hvacMode]
    if (cur && !(cur in m)) m << cur
    return m
}

/** Hubitat thermostatOperatingState from ecobee's running equipment. */
String operatingState(String equipment) {
    List e = (equipment ?: "").split(",").collect { it.trim() }.findAll { it }
    if (e.any { it.startsWith("heatPump") || it.startsWith("auxHeat") }) return "heating"
    if (e.any { it.startsWith("compCool") }) return "cooling"
    if (e.contains("fan")) return "fan only"
    if (e.any { it in ["ventilator", "economizer"] }) return "vent economizer"
    return "idle"
}

String climateName(Map t, String ref) {
    ((t.climates ?: []).find { it.ref == ref }?.name ?: ref) as String
}

/** [currentClimate, holdStatus, holdUntil] from the running event or the program. */
Map holdInfo(Map t) {
    Map e = t.event as Map
    if (!e) return [climate: climateName(t, t.currentClimateRef as String), status: "schedule", until: "none"]
    String end = (e.endDate ?: "") as String
    String until = end >= INDEFINITE_YEAR ? "indefinite" : (end ? "${end} ${((e.endTime ?: '') as String).take(5)}".trim() : "none")
    switch (e.type) {
        case "hold":
            return [climate: e.climate ? climateName(t, e.climate as String) : "Hold", status: "hold", until: until]
        case "vacation":
            return [climate: "Vacation", status: "vacation", until: until]
        case "autoAway":
            return [climate: climateName(t, "away"), status: "smart", until: until]
        case "autoHome":
            return [climate: climateName(t, "home"), status: "smart", until: until]
        case "demandResponse":
            return [climate: "Utility event", status: "utility", until: until]
        default:
            return [climate: climateName(t, t.currentClimateRef as String), status: (e.type ?: "hold") as String, until: until]
    }
}

List thermostatEvents(Map t) {
    List out = []
    Map r = (t.runtime ?: [:]) as Map
    Map s = (t.settings ?: [:]) as Map
    String unit = tempUnit()
    String mode = MODE_FROM_API[s.hvacMode]
    String op = operatingState(t.equipment as String)
    if (r.actualTemperature != null) out << [name: "temperature", value: tempOf(r.actualTemperature as int), unit: unit]
    if (r.actualHumidity != null) out << [name: "humidity", value: r.actualHumidity as int, unit: "%"]
    if (r.desiredHeat != null) out << [name: "heatingSetpoint", value: setpointOf(r.desiredHeat as int), unit: unit]
    if (r.desiredCool != null) out << [name: "coolingSetpoint", value: setpointOf(r.desiredCool as int), unit: unit]
    Integer sp = null
    if (mode == "heat" || mode == "emergency heat") sp = r.desiredHeat as Integer
    else if (mode == "cool") sp = r.desiredCool as Integer
    else if (mode == "auto") sp = (op == "cooling" ? r.desiredCool : r.desiredHeat) as Integer
    if (sp != null) out << [name: "thermostatSetpoint", value: setpointOf(sp), unit: unit]
    if (mode) out << [name: "thermostatMode", value: mode]
    if (s) out << [name: "supportedThermostatModes", value: JsonOutput.toJson(supportedModes(s))]
    out << [name: "supportedThermostatFanModes", value: JsonOutput.toJson(["auto", "circulate", "on"])]
    if (r.desiredFanMode != null || s.fanMinOnTime != null) {
        String fan = r.desiredFanMode == "on" ? "on" : (((s.fanMinOnTime ?: 0) as int) > 0 ? "circulate" : "auto")
        out << [name: "thermostatFanMode", value: fan]
    }
    if (s.fanMinOnTime != null) out << [name: "fanMinOnTime", value: s.fanMinOnTime as int, unit: "min/h"]
    out << [name: "thermostatOperatingState", value: op]
    out << [name: "equipmentStatus", value: (t.equipment ?: "idle") as String]
    if (t.climates != null) {
        Map h = holdInfo(t)
        out << [name: "currentClimate", value: h.climate ?: "unknown"]
        out << [name: "holdStatus", value: h.status]
        out << [name: "holdUntil", value: h.until]
        out << [name: "supportedClimates", value: JsonOutput.toJson((t.climates ?: [])*.name)]
    }
    Map own = (t.sensors ?: []).find { it.type == "thermostat" } as Map
    if (own?.occ != null) out << [name: "motion", value: own.occ == "true" ? "active" : "inactive"]
    if (t.connected != null) out << [name: "connected", value: t.connected ? "true" : "false"]
    out << [name: "healthStatus", value: t.connected == false ? "offline" : "online"]
    return out
}

List sensorEvents(Map s) {
    List out = []
    boolean online = s.temp != null && s.temp != "unknown"
    if (online) out << [name: "temperature", value: tempOf(s.temp as int), unit: tempUnit()]
    if (s.occ != null) out << [name: "motion", value: s.occ == "true" ? "active" : "inactive"]
    if (s.hum != null && s.hum != "unknown") out << [name: "humidity", value: s.hum as int, unit: "%"]
    if (s.inUse != null) out << [name: "inUse", value: s.inUse ? "true" : "false"]
    out << [name: "healthStatus", value: online ? "online" : "offline"]
    return out
}

String tempUnit() { "°${location.temperatureScale ?: 'F'}" }

/** ecobee tenths of °F to the hub's scale, one decimal. */
BigDecimal tempOf(int f10) {
    BigDecimal f = f10 / 10.0
    if ((location.temperatureScale ?: "F") == "C") return ((f - 32) * 5 / 9).setScale(1, BigDecimal.ROUND_HALF_UP)
    return f.setScale(1, BigDecimal.ROUND_HALF_UP)
}

/** Setpoints: whole °F, or half °C. */
BigDecimal setpointOf(int f10) {
    BigDecimal v = tempOf(f10)
    if ((location.temperatureScale ?: "F") == "C") return (v * 2).setScale(0, BigDecimal.ROUND_HALF_UP) / 2
    return v.setScale(0, BigDecimal.ROUND_HALF_UP)
}

/** The hub's scale to ecobee tenths of °F. */
int toF10(Number v) {
    BigDecimal d = v as BigDecimal
    if ((location.temperatureScale ?: "F") == "C") d = d * 9 / 5 + 32
    return (d * 10).setScale(0, BigDecimal.ROUND_HALF_UP) as int
}

// ---------------------------------------------------------------- HTTP helpers

Map apiHeaders() {
    ["Authorization": "Bearer ${state.auth?.access}", "Content-Type": "application/json;charset=UTF-8", "Accept": "application/json"]
}

Map apiGetParams(String path, Map body) {
    [uri: API_HOST, path: path, query: [format: "json", json: JsonOutput.toJson(body)], headers: apiHeaders(),
        contentType: "application/json", timeout: REQUEST_TIMEOUT, textParser: true]
}

/** [ok, json, http, code, auth, msg] for an ecobee API answer. */
Map apiAnswer(resp) {
    int http = statusOf(resp)
    Map json = parseJson(bodyOf(resp))
    Integer code = json?.status?.code as Integer
    boolean auth = (code != null && code in AUTH_CODES) || (http == 401 && code == null)
    boolean ok = http == 200 && (code == null || code == 0) && json != null
    String msg = ok ? "" : (json?.status?.message ? "${json.status.message} (status ${code}, HTTP ${http})" : "HTTP ${http}${errorText(resp)}")
    return [ok: ok, json: json, http: http, code: code, auth: auth, msg: msg]
}

private String errorText(resp) {
    String m = null
    try { m = resp.errorMessage } catch (e) { }
    return m ? " (${m})" : ""
}

int statusOf(resp) {
    try { return (resp.status ?: 0) as int } catch (e) { return 0 }
}

String bodyOf(resp) {
    String b = null
    try { b = resp.data?.toString() } catch (e) { }
    if (b == null) {
        try { b = resp.errorData?.toString() } catch (e) { }
    }
    return b ?: ""
}

Map parseJson(String s) {
    if (!s || !(s.trim().startsWith("{"))) return null
    try { return (Map) new JsonSlurper().parseText(s) } catch (e) { return null }
}

/** Every header with this name, as text values. Hubitat may give a map or entries whose text is "Name: value". */
List headerValues(resp, String name) {
    def headers
    try { headers = resp.getHeaders() } catch (e) { headers = null }
    List out = []
    String prefix = name.toLowerCase() + ":"
    if (headers instanceof Map) {
        headers.each { k, v ->
            if (k?.toString()?.equalsIgnoreCase(name)) (v instanceof Collection ? v : [v]).each { out << headerText(it, name) }
        }
    } else if (headers != null) {
        headers.each { h ->
            String t = headerText(h, null)
            if (t.toLowerCase().startsWith(prefix)) out << t.substring(prefix.length()).trim()
        }
    }
    return out
}

String headerOf(resp, String name) { headerValues(resp, name).find { it } }

/** A header value as text: a String, or a header object (getName/getValue, or text "Name: value"). */
private String headerText(def h, String name) {
    if (h == null) return ""
    String t
    if (h instanceof CharSequence) {
        t = h.toString()
    } else {
        try {
            def n = h.name
            def v = h.value
            if (n != null && v != null) return name ? v.toString() : "${n}: ${v}".toString()
        } catch (e) { }
        t = h.toString()
    }
    if (name && t.toLowerCase().startsWith(name.toLowerCase() + ":")) t = t.substring(name.length() + 1).trim()
    return t
}

/** Add this response's Set-Cookie values to the sign-in's cookie jar (name -> value). */
void mergeCookies(Map lg, resp) {
    Map jar = (lg.cookies ?: [:]) as Map
    headerValues(resp, "Set-Cookie").each { String v ->
        // Several cookies may share one header; a comma starts a new one only when name= follows.
        v.split(/,(?=\s*[A-Za-z0-9_.\-]+=)/).each { String c ->
            String nv = c.split(";")[0].trim()
            int i = nv.indexOf("=")
            if (i <= 0) return
            String k = nv.take(i)
            String val = nv.substring(i + 1)
            boolean expired = c.toLowerCase() =~ /max-age=0|expires=thu, 01 jan 1970/
            if (expired || val == "") jar.remove(k) else jar[k] = val
        }
    }
    lg.cookies = jar
}

/** [host, path, query] of an absolute or relative URL. */
Map splitUrl(String url) {
    String host = null
    String rest = url
    def m = url =~ /^(https?:\/\/[^\/?#]+)(.*)$/
    if (m.find()) {
        host = m.group(1)
        rest = m.group(2) ?: "/"
    }
    int q = rest.indexOf("?")
    String path = q >= 0 ? rest.take(q) : rest
    Map query = [:]
    if (q >= 0) {
        rest.substring(q + 1).split("#")[0].split("&").each { String kv ->
            if (!kv) return
            int i = kv.indexOf("=")
            String k = URLDecoder.decode(i >= 0 ? kv.take(i) : kv, "UTF-8")
            query[k] = i >= 0 ? URLDecoder.decode(kv.substring(i + 1), "UTF-8") : ""
        }
    }
    return [host: host, path: path ?: "/", query: query]
}

String formEncode(Map m) {
    m.findAll { k, v -> v != null }.collect { k, v -> URLEncoder.encode(k as String, "UTF-8") + "=" + URLEncoder.encode(v as String, "UTF-8") }.join("&")
}

/** Auth0 marks a rejected field with data-error-code="…" (or JSON error). */
String errorCodeIn(String body) {
    if (!body) return null
    def m = body =~ /data-error-code="([^"]+)"/
    if (m.find()) return m.group(1)
    m = body =~ /"error"\s*:\s*"([^"]+)"/
    return m.find() ? m.group(1) : null
}

String randomToken() { UUID.randomUUID().toString().replace("-", "") }

/** PKCE S256: base64url(sha256(verifier)) without padding. */
String pkceChallenge(String verifier) {
    byte[] d = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes("US-ASCII"))
    return d.encodeBase64().toString().replace("+", "-").replace("/", "_").replace("=", "")
}

private void logDebug(String msg) {
    if (settings.logEnable) log.debug "Ecobee Connect: ${msg}"
}
