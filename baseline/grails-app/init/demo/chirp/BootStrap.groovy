package demo.chirp

import grails.converters.JSON
import java.text.SimpleDateFormat

class BootStrap {
    def init = {
        JSON.registerObjectMarshaller(Date) { date ->
            def format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
            format.timeZone = TimeZone.getTimeZone('UTC')
            format.format(date)
        }
    }

    def destroy = {
    }

}
