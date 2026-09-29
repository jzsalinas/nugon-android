package py.com.nugon;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NetworkClientTest {
    @Test
    public void publicWebUrlUsesConfiguredBackendOriginWithoutApiPath() {
        assertEquals(
                "https://nugon.prisma.com.py/",
                NetworkClient.publicWebUrl("https://nugon.prisma.com.py/api/v1"));
        assertEquals(
                "https://nugon.example.org/",
                NetworkClient.publicWebUrl("https://nugon.example.org/custom/api/v1"));
    }
}
