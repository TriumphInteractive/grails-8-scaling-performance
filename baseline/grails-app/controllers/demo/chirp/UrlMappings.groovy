package demo.chirp

class UrlMappings {
    static mappings = {
        get "/api/timelines/$handle"(controller: 'timeline', action: 'show')
        get "/api/accounts/$handle"(controller: 'account', action: 'show')
        post "/api/chirps"(controller: 'chirp', action: 'save')
        get "/api/ready"(controller: 'ready', action: 'index')

        "/$namespace/$controller/$action?/$id?(.$format)?" {}
        "/$controller/$action?/$id?(.$format)?"{
            constraints {
                // apply constraints here
            }
        }

        "/"(view:"/index")
        // Browsers request /favicon.ico for pages that declare no icon of their own
        "/favicon.ico"(redirect: [uri: '/assets/favicon.ico', permanent: true])
        "500"(view:'/error')
        "404"(view:'/notFound')

    }
}
