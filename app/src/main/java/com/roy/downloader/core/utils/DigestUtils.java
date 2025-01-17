package com.roy.downloader.core.utils;

import androidx.annotation.NonNull;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.regex.Pattern;

/*
 * Hash utils.
 */

public class DigestUtils {
    private static final String MD5_PATTERN = "[A-Fa-f0-9]{32}";
    private static final String SHA256_PATTERN = "[A-Fa-f0-9]{64}";
    private static final int STREAM_BUFFER_LENGTH = 1024;

    public static String makeSha256Hash(@NonNull FileInputStream is) {
        try (BufferedInputStream bufIs = new BufferedInputStream(is)) {
            return makeHash("SHA-256", bufIs);
        } catch (IOException e) {
            return null;
        }
    }

    public static String makeMd5Hash(@NonNull FileInputStream is) {
        try (BufferedInputStream bufIs = new BufferedInputStream(is)) {
            return makeHash("MD5", bufIs);
        } catch (IOException e) {
            return null;
        }
    }

    public static String makeSha256Hash(@NonNull byte[] bytes) {
        try (ByteArrayInputStream is = new ByteArrayInputStream(bytes)) {
            return makeHash("SHA-256", is);
        } catch (IOException e) {
            return null;
        }
    }

    public static String makeMd5Hash(@NonNull byte[] bytes) {
        try (ByteArrayInputStream is = new ByteArrayInputStream(bytes)) {
            return makeHash("MD5", is);
        } catch (IOException e) {
            return null;
        }
    }

    private static String makeHash(String algorithm, InputStream is) {
        MessageDigest messageDigest;
        try {
            messageDigest = MessageDigest.getInstance(algorithm);
            updateDigest(messageDigest, is);

        } catch (Exception e) {
            return null;
        }

        return digestToString(messageDigest.digest());
    }

    private static void updateDigest(MessageDigest messageDigest, InputStream is) throws IOException {
        byte[] buffer = new byte[STREAM_BUFFER_LENGTH];
        int read = is.read(buffer, 0, STREAM_BUFFER_LENGTH);

        while (read > -1) {
            messageDigest.update(buffer, 0, read);
            read = is.read(buffer, 0, STREAM_BUFFER_LENGTH);
        }
    }

    private static String digestToString(byte[] digest) {
        StringBuilder sha1 = new StringBuilder();
        for (byte b : digest) {
            if ((0xff & b) < 0x10)
                sha1.append("0");
            sha1.append(Integer.toHexString(0xff & b));
        }

        return sha1.toString();
    }

    public static boolean isMd5Hash(@NonNull String hash) {
        return Pattern.compile(MD5_PATTERN).matcher(hash).matches();
    }

    public static boolean isSha256Hash(@NonNull String hash) {
        return Pattern.compile(SHA256_PATTERN).matcher(hash).matches();
    }
}
