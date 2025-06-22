package ai.authvelo;

import com.google.inject.Inject;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.util.GameProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.json.JSONObject;
import org.slf4j.Logger;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.UUID;

@com.velocitypowered.api.plugin.Plugin(
        id = "velocity-fastlogin",
        name = "VelocityFastLogin",
        version = "1.1",
        authors = {"TwojeImie"}
)
public class AuthveloMain {

    private final ProxyServer proxy;
    private final Logger logger;
    private HikariDataSource dataSource;

    @Inject
    public AuthveloMain(ProxyServer proxy, Logger logger) {
        proxy.getChannelRegistrar().register(MinecraftChannelIdentifier.create("authvelo", "verify"));

        this.proxy = proxy;
        this.logger = logger;
        initDatabase();
    }

    private void initDatabase() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            logger.error("Nie znaleziono sterownika MySQL JDBC", e);
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://localhost:3306/minecraft?useSSL=false&allowPublicKeyRetrieval=true");
        config.setUsername("root");
        config.setPassword("root");
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(3000L);
        config.setIdleTimeout(60000L);
        this.dataSource = new HikariDataSource(config);
    }

    @Subscribe
    public void onPreLogin(PreLoginEvent event) {
        String username = event.getUsername();

        if (username == null || username.trim().isEmpty()) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                    Component.text("Niepoprawny nick.").color(NamedTextColor.RED)
            ));
            logger.warn("Niepoprawny nick podczas łączenia się: {}", username);
            return;
        }

        try {
            Boolean knownPremium = getKnownPremiumStatus(username);
            if (knownPremium != null) {
                if (knownPremium) {
                    logger.info("Gracz premium {} już zapisany w bazie.", username);
                    event.setResult(PreLoginEvent.PreLoginComponentResult.forceOnlineMode());
                    return;
                } else {
                    logger.info("Gracz non-premium {} już zapisany w bazie.", username);
                    event.setResult(PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
                    return;
                }
            }

            // Sprawdzenie przez Mojang API
            String encoded = URLEncoder.encode(username, StandardCharsets.UTF_8);
            String sessionUrl = "https://api.mojang.com/users/profiles/minecraft/" + encoded;

            HttpURLConnection conn = (HttpURLConnection) new URL(sessionUrl).openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);

            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder responseBuilder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    responseBuilder.append(line);
                }
                reader.close();

                JSONObject json = new JSONObject(responseBuilder.toString());
                String uuidStr = json.getString("id");
                String uuidFormatted = formatUUID(uuidStr);

                logger.info("Wykryto premium gracza: {}", username);
                savePremiumStatusToDatabase(uuidFormatted, username, true);
                event.setResult(PreLoginEvent.PreLoginComponentResult.forceOnlineMode());
            } else {
                UUID offlineUUID = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
                logger.info("Gracz non-premium: {}", username);
                savePremiumStatusToDatabase(offlineUUID.toString(), username, false);
                event.setResult(PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
            }
        } catch (Exception e) {
            logger.error("Błąd podczas sprawdzania premium gracza {}: {}", username, e.getMessage());
            UUID fallbackUUID = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
            savePremiumStatusToDatabase(fallbackUUID.toString(), username, false);
            event.setResult(PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
        }
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        String username = event.getPlayer().getUsername();
        GameProfile profile = event.getPlayer().getGameProfile();

        boolean hasMojangSignature = profile.getProperties().stream()
                .anyMatch(prop -> prop.getName().equals("textures") && prop.getSignature() != null );

        Boolean recordedPremium = getKnownPremiumStatus(username);

        if (recordedPremium != null && recordedPremium) {
            if (!hasMojangSignature) {
                logger.warn("Gracz {} próbował się podszyć pod konto premium.", username);
                event.setResult(ResultedEvent.ComponentResult.denied(
                        Component.text("Ten nick należy do konta premium. Użyj launchera premium.")
                                .color(NamedTextColor.RED)
                ));
                return;
            } else {
                logger.info("Gracz {} poprawnie zweryfikowany jako premium (z podpisem Mojang).", username);
            }
        }
    }
    private void savePremiumStatusToDatabase(String uuid, String username, boolean isPremium) {
        String sql = "INSERT INTO premium_status (uuid, username, is_premium) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE username = VALUES(username), is_premium = VALUES(is_premium)";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid);
            stmt.setString(2, username);
            stmt.setInt(3, isPremium ? 1 : 0);
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Błąd zapisu premium_status dla {}: {}", username, e.getMessage());
        }
    }

    private Boolean getKnownPremiumStatus(String username) {
        String sql = "SELECT is_premium FROM premium_status WHERE username = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt("is_premium") == 1;
            }
        } catch (SQLException e) {
            logger.error("Błąd odczytu premium_status dla {}: {}", username, e.getMessage());
        }
        return null;
    }

    private String formatUUID(String raw) {
        return raw.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5");
    }
}
