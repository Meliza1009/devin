package com.ghostprotocol.core;

import static org.junit.Assert.*;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.*;
import org.junit.Test;

public class TransportTest {
  @Test
  public void authenticatedEncryptionRejectsTamperingAndOtherRooms() throws Exception {
    RoomCrypto a = new RoomCrypto(RoomCrypto.newCode()), b = new RoomCrypto(RoomCrypto.newCode());
    byte[] plain = "private broadcast".getBytes();
    byte[] cipher = a.encrypt(plain);
    assertArrayEquals(plain, a.decrypt(cipher));
    assertFalse(Arrays.equals(cipher, a.encrypt(plain)));
    try {
      b.decrypt(cipher);
      fail();
    } catch (GeneralSecurityException expected) {
    }
    cipher[cipher.length - 1] ^= 1;
    try {
      a.decrypt(cipher);
      fail();
    } catch (GeneralSecurityException expected) {
    }
  }

  @Test
  public void roomKeysAndSignaturesAreSessionScoped() {
    String key = RoomCrypto.newCode();
    assertEquals(20, key.length());
    RoomCrypto c = new RoomCrypto(RoomCrypto.format(key));
    assertArrayEquals(c.roomTag(), new RoomCrypto(key).roomTag());
    assertFalse(Arrays.equals(c.rotatingSignature("id", 1), c.rotatingSignature("id", 2)));
  }

  @Test
  public void packetsReassembleAtDefaultAndNegotiatedMtu() throws Exception {
    byte[] b = new byte[20000];
    new Random(12).nextBytes(b);
    for (int mtu : List.of(23, 185, 517)) {
      Fragments.Assembler a = new Fragments.Assembler();
      byte[] result = null;
      for (byte[] frame : Fragments.split(b, mtu, 17)) {
        assertTrue(frame.length <= Math.min(512, mtu - 3));
        result = a.accept(frame);
      }
      assertArrayEquals(b, result);
    }
  }

  @Test
  public void missingFragmentIsRejected() throws Exception {
    List<byte[]> frames = Fragments.split(new byte[500], 23, 7);
    Fragments.Assembler a = new Fragments.Assembler();
    a.accept(frames.get(0));
    try {
      a.accept(frames.get(2));
      fail();
    } catch (IOException expected) {
    }
  }

  @Test
  public void advertisementsAuthenticateIdentityAndFitLegacyBle() {
    RoomCrypto crypto = new RoomCrypto(RoomCrypto.newCode());
    String id = RoomCrypto.newId();
    byte[] beacon = crypto.beacon(id, 65536);
    assertTrue(beacon.length + 7 <= 31);
    assertEquals(id, crypto.readBeacon(beacon));
    beacon[8] ^= 1;
    assertNull(crypto.readBeacon(beacon));
    assertNull(new RoomCrypto(RoomCrypto.newCode()).readBeacon(crypto.beacon(id, 1)));
    assertFalse(Arrays.equals(crypto.beacon(id, 1), crypto.beacon(id, 2)));
  }
}
