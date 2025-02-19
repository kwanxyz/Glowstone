package net.glowstone.net.handler.login;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.flowpowered.network.MessageHandler;
import lombok.AllArgsConstructor;
import net.glowstone.EventFactory;
import net.glowstone.entity.meta.profile.GlowPlayerProfile;
import net.glowstone.i18n.ConsoleMessages;
import net.glowstone.i18n.GlowstoneMessages;
import net.glowstone.net.GlowSession;
import net.glowstone.net.http.HttpCallback;
import net.glowstone.net.http.HttpClient;
import net.glowstone.net.message.login.EncryptionKeyResponseMessage;
import net.glowstone.util.UuidUtils;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.URLEncoder;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class EncryptionKeyResponseHandler implements
    MessageHandler<GlowSession, EncryptionKeyResponseMessage> {

    private static final String BASE_URL =
        "https://sessionserver.mojang.com/session/minecraft/hasJoined";
    private static final JSONParser PARSER = new JSONParser();

    private final HttpClient httpClient;

    public EncryptionKeyResponseHandler(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public void handle(GlowSession session, EncryptionKeyResponseMessage message) {
        PrivateKey privateKey = session.getServer().getKeyPair().getPrivate();

        // create rsaCipher
        Cipher rsaCipher;
        try {
            rsaCipher = Cipher.getInstance("RSA"); // NON-NLS
        } catch (GeneralSecurityException ex) {
            ConsoleMessages.Error.Net.Crypt.RSA_INIT_FAILED.log(ex);
            session.disconnect(GlowstoneMessages.Kick.Crypt.RSA_INIT_FAILED.get());
            return;
        }

        // decrypt shared secret
        SecretKey sharedSecret;
        try {
            rsaCipher.init(Cipher.DECRYPT_MODE, privateKey);
            sharedSecret = new SecretKeySpec(rsaCipher.doFinal(message.getSharedSecret()),
                "GCM"); // NON-NLS
        } catch (Exception ex) {
            ConsoleMessages.Warn.Crypt.BAD_SHARED_SECRET.log(ex);
            session.disconnect(GlowstoneMessages.Kick.Crypt.SHARED_SECRET.get());
            return;
        }

        // decrypt verify token
        byte[] verifyToken;
        try {
            rsaCipher.init(Cipher.DECRYPT_MODE, privateKey);
            verifyToken = rsaCipher.doFinal(message.getVerifyToken());
        } catch (Exception ex) {
            ConsoleMessages.Warn.Crypt.BAD_VERIFY_TOKEN.log(ex);
            session.disconnect(GlowstoneMessages.Kick.Crypt.VERIFY_TOKEN.get());
            return;
        }

        // check verify token
        if(!MessageDigest.isEqual(verifyToken, session.getVerifyToken())) {
            session.disconnect(GlowstoneMessages.Kick.Crypt.VERIFY_TOKEN.get());
            return;
        }

        // initialize stream encryption
        session.enableEncryption(sharedSecret);

        // create hash for auth
        String hash;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            digest.update(session.getSessionId().getBytes());
            digest.update(sharedSecret.getEncoded());
            digest.update(session.getServer().getKeyPair().getPublic().getEncoded());

            // BigInteger takes care of sign and leading zeroes
            hash = new BigInteger(digest.digest()).toString(16);
        } catch (NoSuchAlgorithmException ex) {
            ConsoleMessages.Error.Net.Crypt.HASH_FAILED.log(ex);
            session.disconnect(GlowstoneMessages.Kick.Crypt.HASH_FAILED.get());
            return;
        }

        String url = BASE_URL + "?username=" + session.getVerifyUsername() // NON-NLS
            + "&serverId=" + hash; // NON-NLS
        if (session.getServer().shouldPreventProxy()) {
            try {
                // in case we are dealing with an IPv6 address rather than an IPv4 we have to encode
                // it properly
                url += "&ip=" + URLEncoder // NON-NLS
                    .encode(session.getAddress().getAddress().getHostAddress(), "UTF-8");
            } catch (UnsupportedEncodingException encodingEx) {
                // unlikely to happen, because UTF-8 is part of the StandardCharset in Java
                // but if it happens, the client will still able to login, because we won't add the
                // IP parameter
                ConsoleMessages.Warn.Crypt.URL_ENCODE_IP.log(encodingEx);
            }
        }

        httpClient.connect(url, session.getChannel().eventLoop(), new ClientAuthCallback(session));
    }

    @AllArgsConstructor
    private static class ClientAuthCallback implements HttpCallback {

        private final GlowSession session;

        @Override
        public void done(String response) {
            JSONObject json;
            try {
                json = (JSONObject) PARSER.parse(response); // TODO gson here
            } catch (ParseException e) {
                ConsoleMessages.Warn.Crypt.AUTH_FAILED.log(session.getVerifyUsername());
                session.disconnect(GlowstoneMessages.Kick.Crypt.AUTH_FAILED.get());
                return;
            }

            String name = (String) json.get("name"); // NON-NLS
            String id = (String) json.get("id"); // NON-NLS

            // parse UUID
            UUID uuid;
            try {
                uuid = UuidUtils.fromFlatString(id);
            } catch (IllegalArgumentException ex) {
                ConsoleMessages.Error.Net.Crypt.BAD_UUID.log(ex, id);
                session.disconnect(GlowstoneMessages.Kick.Crypt.BAD_UUID.get(id));
                return;
            }

            JSONArray propsArray = (JSONArray) json.get("properties"); // NON-NLS

            // parse properties
            List<ProfileProperty> properties = new ArrayList<>(propsArray.size());
            for (Object obj : propsArray) {
                JSONObject propJson = (JSONObject) obj;
                String propName = (String) propJson.get("name"); // NON-NLS
                String value = (String) propJson.get("value"); // NON-NLS
                String signature = (String) propJson.get("signature"); // NON-NLS
                properties.add(new ProfileProperty(propName, value, signature));
            }

            AsyncPlayerPreLoginEvent event = EventFactory.getInstance()
                .onPlayerPreLogin(name, session.getAddress(), uuid);
            if (event.getLoginResult() != Result.ALLOWED) {
                session.disconnect(event.getKickMessage(), true);
                return;
            }

            // spawn player
            session.getServer().getScheduler().runTask(null, () -> session.setPlayer(
                new GlowPlayerProfile(name, uuid, properties, true)));
        }

        @Override
        public void error(Throwable t) {
            ConsoleMessages.Error.Net.Crypt.AUTH_INTERNAL.log(t);
            session.disconnect(GlowstoneMessages.Kick.Crypt.AUTH_INTERNAL.get(), true);
        }
    }
}
