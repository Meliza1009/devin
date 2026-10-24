package com.ghostprotocol.core;

import java.io.*;
import java.util.*;

/** ATT packet framing with per-link ordered assembly and strict memory limits. */
public final class Fragments {
  private Fragments() {}

  public static List<byte[]> split(byte[] message, int mtu, int id) {
    if (message.length > 131100) throw new IllegalArgumentException("Message too large");
    int size = Math.min(512, Math.max(20, mtu - 3)) - 8;
    int count = (message.length + size - 1) / size;
    List<byte[]> frames = new ArrayList<>();
    for (int n = 0; n < count; n++) {
      final int part = n;
      int start = n * size, end = Math.min(message.length, start + size);
      frames.add(
          Wire.bytes(
              o -> {
                o.writeInt(id);
                o.writeShort(part);
                o.writeShort(count);
                o.write(message, start, end - start);
              }));
    }
    return frames;
  }

  public static final class Assembler {
    private int id = -1, next, total;
    private ByteArrayOutputStream buffer;

    public byte[] accept(byte[] frame) throws IOException {
      if (frame.length < 8 || frame.length > 512) throw new IOException("Invalid ATT frame");
      DataInputStream i = new DataInputStream(new ByteArrayInputStream(frame));
      int current = i.readInt(), part = i.readUnsignedShort(), count = i.readUnsignedShort();
      if (count == 0 || count > 11000 || part >= count)
        throw new IOException("Invalid fragment count");
      if (part == 0) {
        id = current;
        next = 0;
        total = count;
        buffer = new ByteArrayOutputStream();
      }
      if (current != id || part != next || count != total || buffer == null) {
        buffer = null;
        throw new IOException("Out of order fragment");
      }
      byte[] b = new byte[i.available()];
      i.readFully(b);
      if (buffer.size() + b.length > 131100) {
        buffer = null;
        throw new IOException("Message too large");
      }
      buffer.write(b);
      next++;
      if (next == total) {
        byte[] result = buffer.toByteArray();
        buffer = null;
        return result;
      }
      return null;
    }
  }
}
