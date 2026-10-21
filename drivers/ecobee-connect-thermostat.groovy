/**
 * Ecobee Connect Thermostat: an ecobee thermostat, created by the Ecobee Connect app.
 *
 * The app polls ecobee and hands this device its events; only values that
 * changed are sent. Commands go to the app, which gathers them for a moment
 * and sends one request to ecobee, then reads the thermostat back.
 *
 * Copyright (c) 2026 Digital Homesteaders. MIT License; see LICENSE.
 */

metadata {
    definition(name: "Ecobee Connect Thermostat", namespace: "ecobeeconnect", author: "Digital Homesteaders", importUrl: "https://raw.githubusercontent.com/digital-homesteaders/hubitat-ecobee/main/drivers/ecobee-connect-thermostat.groovy") {
        capability "Actuator"
        capability "Sensor"
        capability "Thermostat"
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"
        capability "MotionSensor"
        capability "Refresh"

        attribute "currentClimate", "string"
        attribute "supportedClimates", "JSON_OBJECT"
        attribute "holdStatus", "enum", ["schedule", "hold", "vacation", "smart", "utility"]
        attribute "holdUntil", "string"
        attribute "equipmentStatus", "string"
        attribute "fanMinOnTime", "number"
        attribute "connected", "enum", ["true", "false"]
        attribute "healthStatus", "enum", ["online", "offline"]

        command "setClimate", [[name: "Comfort profile*", type: "STRING", description: "Name, like Home, Away, Sleep or one of your own"]]
        command "setHome"
        command "setAway"
        command "setSleep"
        command "resumeProgram"
        command "setFanMinOnTime", [[name: "Minutes per hour*", type: "NUMBER", description: "0 to 55"]]
    }
    preferences {
        input "txtEnable", "bool", title: "Description text logging", defaultValue: true
        input "logEnable", "bool", title: "Debug logging (turns itself off after 30 minutes)", defaultValue: false
    }
}

// ---------------------------------------------------------------- lifecycle

def installed() {
    sendEvent(name: "supportedThermostatFanModes", value: '["auto","circulate","on"]')
    sendEvent(name: "supportedThermostatModes", value: '["auto","cool","heat","off"]')
}

def updated() {
    if (logEnable) runIn(1800, "logsOff")
}

def logsOff() {
    log.warn "${device.displayName}: debug logging turned off"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

def refresh() {
    parent?.refreshNow()
}

// ---------------------------------------------------------------- thermostat commands

def setThermostatMode(String mode) { parent?.setMode(device.deviceNetworkId, mode) }
def auto() { setThermostatMode("auto") }
def heat() { setThermostatMode("heat") }
def cool() { setThermostatMode("cool") }
def off() { setThermostatMode("off") }
def emergencyHeat() { setThermostatMode("emergency heat") }

def setThermostatFanMode(String fan) { parent?.setFan(device.deviceNetworkId, fan) }
def fanAuto() { setThermostatFanMode("auto") }
def fanOn() { setThermostatFanMode("on") }
def fanCirculate() { setThermostatFanMode("circulate") }
def setFanMinOnTime(minutes) { parent?.setFanMin(device.deviceNetworkId, minutes as BigDecimal) }

def setHeatingSetpoint(temp) { parent?.setHeat(device.deviceNetworkId, temp as BigDecimal) }
def setCoolingSetpoint(temp) { parent?.setCool(device.deviceNetworkId, temp as BigDecimal) }

def setClimate(String name) { parent?.setClimate(device.deviceNetworkId, name) }
def setHome() { setClimate("home") }
def setAway() { setClimate("away") }
def setSleep() { setClimate("sleep") }
def resumeProgram() { parent?.resume(device.deviceNetworkId) }

def setSchedule(schedule) {
    log.warn "${device.displayName}: edit the schedule in the ecobee app; use setClimate or resumeProgram here"
}

// ---------------------------------------------------------------- events from the app

/** Send the events whose value changed. */
void applyEvents(List events) {
    events.each { Map e ->
        if (e.value == null || same(device.currentValue(e.name as String), e.value)) return
        Map evt = new LinkedHashMap(e)
        evt.descriptionText = "${device.displayName} ${e.name} is ${e.value}${e.unit ? ' ' + e.unit : ''}"
        if (txtEnable && !(e.name in ["supportedThermostatModes", "supportedThermostatFanModes", "supportedClimates"])) log.info evt.descriptionText
        if (logEnable) log.debug "${device.displayName}: ${e.name} ${device.currentValue(e.name as String)} -> ${e.value}"
        sendEvent(evt)
    }
}

private boolean same(def current, def value) {
    if (current == null) return false
    try {
        if (value instanceof Number) return (current.toString() as BigDecimal).compareTo(value as BigDecimal) == 0
    } catch (e) {
        return false
    }
    return current.toString() == value.toString()
}
