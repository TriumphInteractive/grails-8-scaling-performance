package demo.chirp

import groovy.transform.CompileStatic

@CompileStatic
class ChirpWriteResult {
    final int status
    final String json
    final String authorHandle

    ChirpWriteResult(int status, String json, String authorHandle = null) {
        this.status = status
        this.json = json
        this.authorHandle = authorHandle
    }
}
