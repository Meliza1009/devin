package com.ghostprotocol.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded, versioned binary encoding. Never deserializes Java objects. */
public final class Wire {
  private Wire() {}

  public interface Writer {
    void write(DataOutputStream out) throws IOException;
  }

  public static byte[] bytes(Writer writer) {
    try {
      ByteArrayOutputStream b = new ByteArrayOutputStream();
      writer.write(new DataOutputStream(b));
      return b.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public static DataInputStream input(byte[] data) throws IOException {
    if (data.length > 131072) throw new IOException("Message too large");
    return new DataInputStream(new ByteArrayInputStream(data));
  }

  public static void string(DataOutputStream out, String s) throws IOException {
    byte[] b = s.getBytes(StandardCharsets.UTF_8);
    if (b.length > 4096) throw new IOException("String too long");
    out.writeInt(b.length);
    out.write(b);
  }

  public static String string(DataInputStream in) throws IOException {
    return new String(blob(in, 4096), StandardCharsets.UTF_8);
  }

  public static void blob(DataOutputStream out, byte[] b) throws IOException {
    out.writeInt(b.length);
    out.write(b);
  }

  public static byte[] blob(DataInputStream in, int max) throws IOException {
    int n = in.readInt();
    if (n < 0 || n > max) throw new IOException("Invalid length");
    byte[] b = new byte[n];
    in.readFully(b);
    return b;
  }

  public static int count(DataInputStream in, int max) throws IOException {
    int n = in.readInt();
    if (n < 0 || n > max) throw new IOException("Invalid count");
    return n;
  }

  public static void strings(DataOutputStream out, Collection<String> list) throws IOException {
    out.writeInt(list.size());
    for (String s : list) string(out, s);
  }

  public static List<String> strings(DataInputStream in, int max) throws IOException {
    int n = count(in, max);
    List<String> l = new ArrayList<>();
    for (int i = 0; i < n; i++) l.add(string(in));
    return l;
  }
}
