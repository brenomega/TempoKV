package io.tempokv.integration;

import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Smoke-tests UC-04 through the public SQL endpoint and the shared RESP-visible state. */
class Uc04SqlInterfaceSmokeTest {
    @TempDir
    Path temporaryDirectory;

    /** Executes fragmented and pipelined SQL while preserving command equivalence and typed errors. */
    @Test
    void smokeTestExecutesSqlMutationsQueriesAndErrors() throws Exception {
        try (Uc05HistoricalReadSmokeTest.ServerFixture fixture =
                        Uc05HistoricalReadSmokeTest.ServerFixture.start(
                                temporaryDirectory);
                SqlTestClient sql = SqlTestClient.connect(fixture.server());
                Socket resp = fixture.client()) {
            sql.sendFragments(
                    "UPSERT INTO tempokv (key, value) VALUES ('profile', '",
                    "Ada');");
            assertEquals("status\nOK\n\n", sql.readResponse());

            sql.send(
                    "SELECT key, value FROM tempokv WHERE key = 'profile';"
                            + "DELETE FROM tempokv WHERE key = 'profile';"
                            + "SELECT value FROM tempokv WHERE key = 'profile';");
            assertEquals(
                    "key\tvalue\nprofile\tAda\n\n",
                    sql.readResponse());
            assertEquals("affected\n1\n\n", sql.readResponse());
            assertEquals("value\n\n", sql.readResponse());

            resp.getOutputStream().write(
                    request("GET", "profile").getBytes(StandardCharsets.UTF_8));
            resp.getOutputStream().flush();
            assertEquals("$-1\r\n", readExactly(resp.getInputStream(), 5));

            sql.send(
                    "SELECT @;"
                            + "SELECT value FROM;"
                            + "SELECT value FROM tempokv;");
            assertEquals(
                    "ERROR\tLEXICAL\t1:8\tunexpected character '@'\n\n",
                    sql.readResponse());
            assertEquals(
                    "ERROR\tSYNTAX\t1:18\tunexpected token ';'\n\n",
                    sql.readResponse());
            assertEquals(
                    "ERROR\tSEMANTIC\t-\tpoint lookup requires WHERE key = '...'\n\n",
                    sql.readResponse());
        }
    }

    private static String request(String... values) {
        StringBuilder request =
                new StringBuilder("*").append(values.length).append("\r\n");
        for (String value : values) {
            request.append('$')
                    .append(value.getBytes(StandardCharsets.UTF_8).length)
                    .append("\r\n")
                    .append(value)
                    .append("\r\n");
        }
        return request.toString();
    }

    private static String readExactly(InputStream input, int length)
            throws Exception {
        return new String(input.readNBytes(length), StandardCharsets.UTF_8);
    }
}
