/**
 * Ecobee Connect Sensor: an ecobee room sensor, created by the Ecobee Connect app.
 *
 * Readings ride on its thermostat's updates; only values that changed are sent.
 * healthStatus is offline while ecobee reports the sensor's temperature as unknown.
 *
 * Copyright (c) 2026 Digital Homesteaders. MIT License; see LICENSE.
 */

metadata {
    definition(name: "Ecobee Connect Sensor", namespace: "ecobeeconnect", author: "Digital Homesteaders", importUrl: "https://raw.githubusercontent.com/digital-homesteaders/hubitat-ecobee/main/drivers/ecobee-connect-sensor.groovy") {
        capability "Sensor"
        capability "TemperatureMeasurement"
        capability "MotionSensor"
        capability "RelativeHumidityMeasurement"
        capability "Refresh"

        attribute "inUse", "enum", ["true", "false"]
        attribute "healthStatus", "enum", ["online", "offline"]
    }
    preferences {
        input "txtEnable", "bool", title: "Description text logging", defaultValue: true
        input "logEnable", "bool", title: "Debug logging (turns itself off after 30 minutes)", defaultValue: false
    }
}

// ---------------------------------------------------------------- lifecycle

def installed() {
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

// ---------------------------------------------------------------- events from the app

/** Send the events whose value changed. */
void applyEvents(List events) {
    events.each { Map e ->
        if (e.value == null || same(device.currentValue(e.name as String), e.value)) return
        Map evt = new LinkedHashMap(e)
        evt.descriptionText = "${device.displayName} ${e.name} is ${e.value}${e.unit ? ' ' + e.unit : ''}"
        if (txtEnable) log.info evt.descriptionText
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
