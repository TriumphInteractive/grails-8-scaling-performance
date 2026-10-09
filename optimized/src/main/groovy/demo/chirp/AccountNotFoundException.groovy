package demo.chirp

import groovy.transform.CompileStatic

@CompileStatic
class AccountNotFoundException extends RuntimeException {
    AccountNotFoundException(String handle) {
        super("Account not found: ${handle}")
    }
}
