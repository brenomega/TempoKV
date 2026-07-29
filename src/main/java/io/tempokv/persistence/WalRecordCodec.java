package io.tempokv.persistence;

import io.tempokv.transaction.CommitRecord;
import io.tempokv.transaction.Mutation;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/** Encodes self-delimiting, checksummed and versioned WAL commit records. */
public final class WalRecordCodec {
    private static final int MAGIC = 0x544B5631;
    private static final short FORMAT_VERSION = 1;
    private static final int MAX_RECORD_BYTES = 64 * 1024 * 1024;

    /** Serializes one record with a checksum that detects corruption before replay. */
    public byte[] encode(CommitRecord record) throws IOException {
        ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
        try (DataOutputStream payload = new DataOutputStream(payloadBytes)) {
            payload.writeLong(record.version());
            payload.writeLong(record.committedAt().toEpochMilli());
            payload.writeInt(record.mutations().size());
            for (Mutation mutation : record.mutations()) writeMutation(payload, mutation);
        }
        byte[] body = payloadBytes.toByteArray();
        CRC32 checksum = new CRC32(); checksum.update(body);
        ByteArrayOutputStream result = new ByteArrayOutputStream(body.length + 18);
        try (DataOutputStream output = new DataOutputStream(result)) {
            output.writeInt(MAGIC); output.writeShort(FORMAT_VERSION); output.writeInt(body.length);
            output.write(body); output.writeInt((int) checksum.getValue());
        }
        return result.toByteArray();
    }

    /** Decodes a full record, rejecting unsupported formats and checksum mismatches. */
    public CommitRecord decode(byte[] encoded) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC) throw new IOException("Invalid WAL record magic");
            if (input.readShort() != FORMAT_VERSION) throw new IOException("Unsupported WAL format version");
            int length = input.readInt();
            if (length < 0 || length > MAX_RECORD_BYTES || input.available() != length + Integer.BYTES) throw new IOException("Invalid WAL record length");
            byte[] body = input.readNBytes(length);
            int expected = input.readInt(); CRC32 checksum = new CRC32(); checksum.update(body);
            if ((int) checksum.getValue() != expected) throw new IOException("WAL checksum mismatch");
            return readBody(body);
        } catch (EOFException exception) { throw new IOException("Truncated WAL record", exception); }
    }

    /** Returns the byte size of the first complete frame, or zero for an incomplete tail. */
    public int frameLength(byte[] bytes, int offset) throws IOException {
        int header = Integer.BYTES + Short.BYTES + Integer.BYTES;
        if (bytes.length - offset < header) return 0;
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes, offset, bytes.length - offset))) {
            if (input.readInt() != MAGIC) throw new IOException("Invalid WAL record magic");
            if (input.readShort() != FORMAT_VERSION) throw new IOException("Unsupported WAL format version");
            int length = input.readInt();
            if (length < 0 || length > MAX_RECORD_BYTES) throw new IOException("Invalid WAL record length");
            int frame = header + length + Integer.BYTES;
            return bytes.length - offset < frame ? 0 : frame;
        }
    }

    private static void writeMutation(DataOutputStream output, Mutation mutation) throws IOException {
        writeBytes(output, mutation.key().getBytes(StandardCharsets.UTF_8)); output.writeByte(mutation.type().ordinal());
        writeNullableBytes(output, mutation.value()); output.writeBoolean(mutation.expiresAt() != null);
        if (mutation.expiresAt() != null) output.writeLong(mutation.expiresAt().toEpochMilli());
        output.writeBoolean(mutation.restoredFromVersion() != null);
        if (mutation.restoredFromVersion() != null) output.writeLong(mutation.restoredFromVersion());
    }
    private static CommitRecord readBody(byte[] body) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
            long version = input.readLong(); Instant time = Instant.ofEpochMilli(input.readLong()); int count = input.readInt();
            if (count < 1 || count > 1_000_000) throw new IOException("Invalid WAL mutation count");
            List<Mutation> mutations = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                String key = new String(readBytes(input), StandardCharsets.UTF_8); int ordinal = input.readUnsignedByte();
                if (ordinal >= Mutation.Type.values().length) throw new IOException("Invalid WAL mutation type");
                byte[] value = readNullableBytes(input); Instant expiry = input.readBoolean() ? Instant.ofEpochMilli(input.readLong()) : null;
                Long restored = input.readBoolean() ? input.readLong() : null;
                mutations.add(new Mutation(key, Mutation.Type.values()[ordinal], value, expiry, restored));
            }
            if (input.available() != 0) throw new IOException("Unexpected WAL payload bytes");
            return new CommitRecord(version, time, mutations);
        } catch (IllegalArgumentException exception) { throw new IOException("Invalid WAL mutation", exception); }
    }
    private static void writeNullableBytes(DataOutputStream output, byte[] value) throws IOException { output.writeInt(value == null ? -1 : value.length); if (value != null) output.write(value); }
    private static void writeBytes(DataOutputStream output, byte[] value) throws IOException { output.writeInt(value.length); output.write(value); }
    private static byte[] readNullableBytes(DataInputStream input) throws IOException { int size = input.readInt(); if (size == -1) return null; return readSized(input, size); }
    private static byte[] readBytes(DataInputStream input) throws IOException { return readSized(input, input.readInt()); }
    private static byte[] readSized(DataInputStream input, int size) throws IOException {
        if (size < 0 || size > MAX_RECORD_BYTES) throw new IOException("Invalid WAL byte field length");
        byte[] value = input.readNBytes(size);
        if (value.length != size) throw new IOException("Truncated WAL byte field");
        return value;
    }
}
