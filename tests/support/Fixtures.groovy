/**
 * Synthetic ecobee answers shaped like the real API (thermostatSummary and
 * thermostat), for one heat-pump thermostat with two room sensors. The values
 * are made up; the shapes follow ecobee's API documentation.
 */

class Fixtures {
    static final String ID = "411111111111"

    static Map thermostat() {
        [
            identifier: ID, name: "Main Floor", thermostatRev: "261017070000",
            runtime: [runtimeRev: "261017070100", connected: true, actualTemperature: 705, actualHumidity: 41,
                desiredHeat: 680, desiredCool: 760, desiredFanMode: "auto"],
            settings: [hvacMode: "heat", heatCoolMinDelta: 50, hasHeatPump: true, heatStages: 1, coolStages: 1,
                autoHeatCoolFeatureEnabled: true, fanMinOnTime: 0],
            program: [currentClimateRef: "home", climates: [
                [name: "Home", climateRef: "home"], [name: "Away", climateRef: "away"],
                [name: "Sleep", climateRef: "sleep"], [name: "Work From Home", climateRef: "smart1"]]],
            events: [
                [type: "vacation", running: false, holdClimateRef: "", endDate: "2026-12-30", endTime: "08:00:00"],
                [type: "hold", running: true, holdClimateRef: "", endDate: "2026-10-17", endTime: "18:00:00"]],
            remoteSensors: [
                [id: "ei:0", name: "Main Floor", type: "thermostat", inUse: true, capability: [
                    [id: "1", type: "temperature", value: "705"], [id: "2", type: "humidity", value: "41"],
                    [id: "3", type: "occupancy", value: "true"]]],
                [id: "rs:100", name: "Bedroom", type: "ecobee3_remote_sensor", inUse: true, capability: [
                    [id: "1", type: "temperature", value: "688"], [id: "2", type: "occupancy", value: "false"]]],
                [id: "rs2:101", name: "Basement", type: "ecobee3_remote_sensor", inUse: false, capability: [
                    [id: "1", type: "temperature", value: "unknown"], [id: "2", type: "occupancy", value: "false"]]]]
        ]
    }

    static Map summary(String thermostatRev = "261017070000", String runtimeRev = "261017070100", String equipment = "heatPump,fan") {
        [
            thermostatCount: 1,
            revisionList: ["${ID}:Main Floor:true:${thermostatRev}:261017065000:${runtimeRev}:261017070000".toString()],
            statusList: ["${ID}:${equipment}".toString()],
            status: [code: 0, message: ""]
        ]
    }

    /** A thermostat answer holding only what the selection asked for. */
    static Map thermostatAnswer(Map selection, Map t = thermostat()) {
        Map out = [identifier: t.identifier, name: t.name, thermostatRev: t.thermostatRev]
        if (selection.includeRuntime) out.runtime = t.runtime
        if (selection.includeSensors) out.remoteSensors = t.remoteSensors
        if (selection.includeSettings) out.settings = t.settings
        if (selection.includeProgram) out.program = t.program
        if (selection.includeEvents) out.events = t.events
        [page: [page: 1, totalPages: 1, pageSize: 1, total: 1], thermostatList: [out], status: [code: 0, message: ""]]
    }
}
