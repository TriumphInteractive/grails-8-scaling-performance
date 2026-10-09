package demo.chirp;

// Warmup is a separate invocation. Do not compare a cold JVM with a warmed JVM.
import java.time.Duration;
import java.util.UUID;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

public final class ChirpSimulation extends Simulation {
    private final int users = Integer.getInteger("users", 40);
    private final Duration ramp = Duration.ofSeconds(Long.getLong("rampSeconds", 15L));
    private final Duration hold = Duration.ofSeconds(Long.getLong("holdSeconds", 45L));

    private final HttpProtocolBuilder protocol = http
        .baseUrl(System.getProperty("baseUrl", "http://localhost:8080"))
        .acceptHeader("application/json")
        .shareConnections();

    private final ScenarioBuilder traffic = scenario("Chirp traffic")
        .forever().on(
            feed(csv("handles.csv").random()),
            randomSwitch().on(
            percent(85.0).then(
                exec(http("Timeline")
                    .get("/api/timelines/#{handle}?max=20")
                    .check(status().is(200), jsonPath("$.chirps").exists()))
            ),
            percent(10.0).then(
                exec(http("Account")
                    .get("/api/accounts/#{handle}")
                    .check(status().is(200)))
            ),
            percent(5.0).then(
                exec(session -> session.set("body", "loadtest-" + UUID.randomUUID()))
                    .exec(http("Create chirp")
                        .post("/api/chirps")
                        .asJson()
                        .body(StringBody("{\"handle\":\"#{handle}\",\"body\":\"#{body}\"}"))
                        .check(status().is(201)))
            )
        ));

    public ChirpSimulation() {
        setUp(traffic.injectClosed(
            rampConcurrentUsers(0).to(users).during(ramp),
            constantConcurrentUsers(users).during(hold)
        )).protocols(protocol)
            .maxDuration(ramp.plus(hold).plusSeconds(5))
            .assertions(global().failedRequests().percent().lt(1.0));
    }
}
