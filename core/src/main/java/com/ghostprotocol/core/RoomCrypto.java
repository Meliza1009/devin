package com.ghostprotocol.core;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Session-only group encryption for trusted party play; no identity or role is advertised. */
public final class RoomCrypto {
  private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
  private static final SecureRandom RANDOM = new SecureRandom();
  private final byte[] key;

  public RoomCrypto(String code) {
    String normalized = normalize(code);
    if (normalized.length() != 20 || !normalized.chars().allMatch(c -> ALPHABET.indexOf(c) >= 0))
      throw new IllegalArgumentException("Enter the 20-character room key.");
    key = hash(("GhostProtocol/v1/" + normalized).getBytes(StandardCharsets.UTF_8));
  }

  public static String newCode() {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < 20; i++) b.append(ALPHABET.charAt(RANDOM.nextInt(32)));
    return b.toString();
  }

  public static String normalize(String code) {
    return code.toUpperCase(Locale.ROOT)
        .replaceAll("[\\s-]", "")
        .replace('O', '0')
        .replace('I', '1')
        .replace('L', '1');
  }

  public static String format(String code) {
    String c = normalize(code);
    return String.join(
        "-", c.substring(0, 5), c.substring(5, 10), c.substring(10, 15), c.substring(15, 20));
  }

  public static String newId() {
    byte[] b = new byte[8];
    RANDOM.nextBytes(b);
    return hex(b);
  }

  public byte[] roomTag() {
    return Arrays.copyOf(hash(key), 4);
  }

  public byte[] rotatingSignature(String id, int rotation) {
    try {
      Mac m = Mac.getInstance("HmacSHA256");
      m.init(new SecretKeySpec(key, "HmacSHA256"));
      return Arrays.copyOf(m.doFinal((id + ":" + rotation).getBytes(StandardCharsets.UTF_8)), 6);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public byte[] encrypt(byte[] plain) {
    try {
      byte[] nonce = new byte[12];
      RANDOM.nextBytes(nonce);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
      c.updateAAD(new byte[] {'G', 'P', 1});
      byte[] body = c.doFinal(plain);
      byte[] out = Arrays.copyOf(nonce, nonce.length + body.length);
      System.arraycopy(body, 0, out, 12, body.length);
      return out;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Fits legacy advertising: 23 manufacturer bytes + 4 AD overhead + 3 flag bytes. */
  public byte[] beacon(String id, int rotation) {
    byte[] out = new byte[23];
    out[0] = 'G';
    out[1] = 'P';
    out[2] = 1;
    System.arraycopy(roomTag(), 0, out, 3, 4);
    System.arraycopy(unhex(id), 0, out, 7, 8);
    int counter = rotation & 0xffff;
    out[15] = (byte) (counter >>> 8);
    out[16] = (byte) counter;
    System.arraycopy(rotatingSignature(id, counter), 0, out, 17, 6);
    return out;
  }

  public String readBeacon(byte[] data) {
    if (data == null
        || data.length != 23
        || data[0] != 'G'
        || data[1] != 'P'
        || data[2] != 1
        || !Arrays.equals(roomTag(), Arrays.copyOfRange(data, 3, 7))) return null;
    String id = hex(Arrays.copyOfRange(data, 7, 15));
    int counter = ((data[15] & 255) << 8) | (data[16] & 255);
    return MessageDigest.isEqual(rotatingSignature(id, counter), Arrays.copyOfRange(data, 17, 23))
        ? id
        : null;
  }

  public byte[] decrypt(byte[] encrypted) throws GeneralSecurityException {
    if (encrypted.length < 28 || encrypted.length > 131100)
      throw new GeneralSecurityException("Invalid envelope");
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(
        Cipher.DECRYPT_MODE,
        new SecretKeySpec(key, "AES"),
        new GCMParameterSpec(128, encrypted, 0, 12));
    c.updateAAD(new byte[] {'G', 'P', 1});
    return c.doFinal(encrypted, 12, encrypted.length - 12);
  }

  public static byte[] hash(byte[] b) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(b);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String hex(byte[] b) {
    StringBuilder s = new StringBuilder();
    for (byte a : b) s.append(String.format(Locale.ROOT, "%02x", a & 255));
    return s.toString();
  }

  public static byte[] unhex(String s) {
    byte[] b = new byte[s.length() / 2];
    for (int j = 0; j < b.length; j++)
      b[j] = (byte) Integer.parseInt(s.substring(j * 2, j * 2 + 2), 16);
    return b;
  }
}
