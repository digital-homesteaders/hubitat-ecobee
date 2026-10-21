/**
 * The app's ecobee mapping as plain functions: summary parsing, merging
 * partial fetches, events for the thermostat and sensors in °F and °C,
 * modes, operating state, holds, write bodies and URL/cookie helpers.
 */

import groovy.json.JsonSlurper
import groovy.transform.Field
import static Check.check

@Field File root = new File(getClass().protectionDomain.codeSource.location.path).parentFile.parentFile
@Field Class Fx = Fixtures

Hub hub = new Hub(root)
def app = hub.app()

Map ev(List evts) { evts.collectEntries { [(it.name): it.value] } }

check("summary lines parse into revisions and equipment") {
    Map s = app.parseSummary(Fx.summary())
    assert s.keySet() == [Fx.ID] as Set
    Map t = s[Fx.ID]
    assert t.name == "Main Floor" && t.connected && t.thermostatRev == "261017070000" && t.runtimeRev == "261017070100"
    assert t.equipment == "heatPump,fan"
}

check("summary with nothing running and short lines") {
    Map s = app.parseSummary([revisionList: ["1:A:false:1:2:3:4", "bad:line"], statusList: ["1:"]])
    assert s.keySet() == ["1"] as Set
    assert s["1"].equipment == "" && s["1"].connected == false
}

check("full thermostat in °F") {
    hub.temperatureScale = "F"
    Map t = app.mergeThermostat([:], Fx.thermostat())
    t.equipment = "heatPump,fan"
    t.connected = true
    Map e = ev(app.thermostatEvents(t))
    assert e.temperature == 70.5
    assert e.humidity == 41
    assert e.heatingSetpoint == 68 && e.coolingSetpoint == 76
    assert e.thermostatSetpoint == 68
    assert e.thermostatMode == "heat"
    assert new JsonSlurper().parseText(e.supportedThermostatModes) == ["auto", "cool", "emergency heat", "heat", "off"]
    assert e.thermostatFanMode == "auto"
    assert e.thermostatOperatingState == "heating"
    assert e.equipmentStatus == "heatPump,fan"
    assert e.currentClimate == "Hold" && e.holdStatus == "hold" && e.holdUntil == "2026-10-17 18:00"
    assert new JsonSlurper().parseText(e.supportedClimates) == ["Home", "Away", "Sleep", "Work From Home"]
    assert e.motion == "active"
    assert e.healthStatus == "online"
}

check("°C: temperatures one decimal, setpoints to the half degree") {
    hub.temperatureScale = "C"
    Map t = app.mergeThermostat([:], Fx.thermostat())
    Map e = ev(app.thermostatEvents(t))
    assert e.temperature == 21.4      // 70.5 °F
    assert e.heatingSetpoint == 20.0  // 68 °F
    assert e.coolingSetpoint == 24.5  // 76 °F = 24.44
    assert app.toF10(20.0) == 680
    assert app.toF10(21.5) == 707
    hub.temperatureScale = "F"
    assert app.toF10(68) == 680
    assert app.toF10(68.5) == 685
}

check("a runtime-only fetch keeps cached settings, program and events") {
    Map full = app.mergeThermostat([:], Fx.thermostat())
    Map raw = Fx.thermostat()
    raw.remove("settings"); raw.remove("program"); raw.remove("events")
    raw.runtime.actualTemperature = 712
    Map t = app.mergeThermostat(full, raw)
    assert t.settings.hvacMode == "heat"
    assert t.climates.size() == 4
    assert t.event.type == "hold"
    assert t.runtime.actualTemperature == 712
}

check("operating state from equipment") {
    assert app.operatingState("") == "idle"
    assert app.operatingState(null) == "idle"
    assert app.operatingState("fan") == "fan only"
    assert app.operatingState("compCool1,fan") == "cooling"
    assert app.operatingState("auxHeat1") == "heating"
    assert app.operatingState("heatPump2,auxHeat1,fan") == "heating"
    assert app.operatingState("ventilator") == "vent economizer"
    assert app.operatingState("humidifier") == "idle"
}

check("supported modes follow the equipment") {
    assert app.supportedModes([hasHeatPump: false, heatStages: 1, coolStages: 0, autoHeatCoolFeatureEnabled: true, hvacMode: "heat"]) == ["heat", "off"]
    assert app.supportedModes([hasHeatPump: false, heatStages: 2, coolStages: 1, autoHeatCoolFeatureEnabled: false, hvacMode: "cool"]) == ["cool", "heat", "off"]
    assert app.supportedModes([hasHeatPump: true, heatStages: 0, coolStages: 1, autoHeatCoolFeatureEnabled: true, hvacMode: "auto"]) == ["auto", "cool", "heat", "off"]
    assert app.supportedModes(null) == ["off"]
}

check("auto mode setpoint follows what is running") {
    Map t = app.mergeThermostat([:], Fx.thermostat())
    t.settings.hvacMode = "auto"
    t.equipment = "compCool1"
    assert ev(app.thermostatEvents(t)).thermostatSetpoint == 76
    t.equipment = ""
    assert ev(app.thermostatEvents(t)).thermostatSetpoint == 68
    t.settings.hvacMode = "off"
    assert !ev(app.thermostatEvents(t)).containsKey("thermostatSetpoint")
}

check("fan mode: on hold, circulate by minimum on time, else auto") {
    Map t = app.mergeThermostat([:], Fx.thermostat())
    t.runtime.desiredFanMode = "on"
    assert ev(app.thermostatEvents(t)).thermostatFanMode == "on"
    t.runtime.desiredFanMode = "auto"
    t.settings.fanMinOnTime = 15
    assert ev(app.thermostatEvents(t)).thermostatFanMode == "circulate"
    assert ev(app.thermostatEvents(t)).fanMinOnTime == 15
}

check("holds: climate hold, indefinite, smart away, vacation, schedule") {
    Map t = app.mergeThermostat([:], Fx.thermostat())
    t.event = [type: "hold", climate: "sleep", endDate: "2035-01-01", endTime: "00:00:00"]
    assert app.holdInfo(t) == [climate: "Sleep", status: "hold", until: "indefinite"]
    t.event = [type: "autoAway", climate: "", endDate: "2026-10-17", endTime: "17:00:00"]
    assert app.holdInfo(t).climate == "Away" && app.holdInfo(t).status == "smart"
    t.event = [type: "vacation", endDate: "2026-12-30", endTime: "08:00:00"]
    assert app.holdInfo(t) == [climate: "Vacation", status: "vacation", until: "2026-12-30 08:00"]
    t.event = null
    t.currentClimateRef = "smart1"
    assert app.holdInfo(t) == [climate: "Work From Home", status: "schedule", until: "none"]
}

check("sensors: temperature, motion, unknown is offline") {
    hub.temperatureScale = "F"
    Map t = app.mergeThermostat([:], Fx.thermostat())
    Map bed = t.sensors.find { it.id == "rs:100" }
    Map base = t.sensors.find { it.id == "rs2:101" }
    assert ev(app.sensorEvents(bed)) == [temperature: 68.8, motion: "inactive", inUse: "true", healthStatus: "online"]
    Map b = ev(app.sensorEvents(base))
    assert !b.containsKey("temperature") && b.healthStatus == "offline" && b.inUse == "false"
}

check("write body: setpoints keep the deadband and use the hold setting") {
    app.settings.holdType = "nextTransition"
    Map t = app.mergeThermostat([:], Fx.thermostat())
    Map b = app.writeBody(Fx.ID, [heat: 740], t)
    assert b.selection == [selectionType: "thermostats", selectionMatch: Fx.ID]
    assert b.functions == [[type: "setHold", params: [holdType: "nextTransition", heatHoldTemp: 740, coolHoldTemp: 790]]]
    b = app.writeBody(Fx.ID, [cool: 700], t)
    assert b.functions[0].params.heatHoldTemp == 650 && b.functions[0].params.coolHoldTemp == 700
    app.settings.holdType = "holdHours"
    app.settings.holdHours = 3
    b = app.writeBody(Fx.ID, [fan: "on"], t)
    assert b.functions[0].params == [holdType: "holdHours", holdHours: 3, heatHoldTemp: 680, coolHoldTemp: 760, fan: "on"]
    app.settings.holdType = "nextTransition"
}

check("write body: mode and fan minimum go in settings, resume before a climate hold") {
    Map t = app.mergeThermostat([:], Fx.thermostat())
    Map b = app.writeBody(Fx.ID, [mode: "auxHeatOnly", fanMin: 20], t)
    assert b.thermostat == [settings: [hvacMode: "auxHeatOnly", fanMinOnTime: 20]]
    assert !b.functions
    b = app.writeBody(Fx.ID, [resume: true, climate: "away"], t)
    assert b.functions*.type == ["resumeProgram", "setHold"]
    assert b.functions[1].params.holdClimateRef == "away"
    assert app.writeBody(Fx.ID, [:], t) == null
}

check("URLs: absolute and relative, query decoded") {
    Map u = app.splitUrl("https://auth.ecobee.com/u/login/password?state=a%2Bb&x=1")
    assert u == [host: "https://auth.ecobee.com", path: "/u/login/password", query: [state: "a+b", x: "1"]]
    u = app.splitUrl("/authorize/resume?state=Z")
    assert u.host == null && u.path == "/authorize/resume" && u.query.state == "Z"
    u = app.splitUrl("https://www.ecobee.com/home/authCallback?code=C1&state=S")
    assert u.query.code == "C1"
}

check("cookies: several headers, entries, shared header, deletions") {
    Map lg = [cookies: [old: "1", gone: "x"]]
    def resp = new FakeResp(status: 302, headers: ["Set-Cookie": [
        new FakeHeaderEntry(name: "Set-Cookie", value: "auth0=abc; Path=/; Secure; HttpOnly"),
        "did=d1; Path=/; Expires=Wed, 21 Oct 2027 07:28:00 GMT, did_compat=d2; Path=/",
        "gone=; Max-Age=0; Path=/"]])
    app.mergeCookies(lg, resp)
    assert lg.cookies == [old: "1", auth0: "abc", did: "d1", did_compat: "d2"]
}

check("PKCE challenge matches RFC 7636's example") {
    assert app.pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk") == "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
}

check("form encoding and Auth0 error codes") {
    assert app.formEncode([a: "x y", b: "p&q=", c: null]) == "a=x+y&b=p%26q%3D"
    assert app.errorCodeIn('<span data-error-code="wrong-credentials">') == "wrong-credentials"
    assert app.errorCodeIn('{"error":"invalid_grant"}') == "invalid_grant"
    assert app.errorCodeIn("<html></html>") == null
}

Check.finish()
