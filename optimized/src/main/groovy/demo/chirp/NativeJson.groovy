package demo.chirp

import groovy.json.JsonSlurper

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class NativeJson {
    static Date toDate(Object value, ZoneId zone = ZoneId.systemDefault()) {
        if (value instanceof Date) {
            return (Date) value
        }
        if (value instanceof LocalDateTime) {
            return Date.from(((LocalDateTime) value).atZone(zone).toInstant())
        }
        if (value instanceof Instant) {
            return Date.from((Instant) value)
        }
        throw new IllegalArgumentException(String.valueOf(value))
    }

    static List<String> list(Object value) {
        if (value == null) {
            return []
        }
        String text = value.toString()
        if (text.isEmpty() || text == 'null') {
            return []
        }
        ((List) new JsonSlurper().parseText(text)).collect { it == null ? null : it.toString() }
    }
}
