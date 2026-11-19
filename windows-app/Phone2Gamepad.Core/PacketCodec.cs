using System.Buffers.Binary;
using System.Security.Cryptography;

namespace Phone2Gamepad.Core;

public enum PacketError { None, Length, Magic, Version, Type, Session, Tag }

public enum SequenceResult { Accepted, Duplicate, OutOfOrder }

public readonly record struct DecodedPacket(
    byte Type, uint SessionId, uint Sequence, uint Timestamp,
    GamepadState State, uint Echo, ushort EchoDelay);

/// <summary>Encode/decode/validate input-channel packets per PROTOCOL.md sections 3-4.</summary>
public static class PacketCodec
{
    public static byte[] EncodeState(uint sessionId, uint sequence, uint timestamp, in GamepadState s, byte[] key)
    {
        var b = new byte[ProtocolConstants.StateLength];
        WriteHeader(b, ProtocolConstants.TypeState, sessionId, sequence);
        BinaryPrimitives.WriteUInt32LittleEndian(b.AsSpan(12), timestamp);
        BinaryPrimitives.WriteUInt16LittleEndian(b.AsSpan(16), (ushort)(s.Buttons & GamepadButtons.DefinedMask));
        b[18] = (byte)(s.Dpad & DpadFlags.DefinedMask);
        b[19] = 0;
        BinaryPrimitives.WriteInt16LittleEndian(b.AsSpan(20), s.LeftX);
        BinaryPrimitives.WriteInt16LittleEndian(b.AsSpan(22), s.LeftY);
        BinaryPrimitives.WriteInt16LittleEndian(b.AsSpan(24), s.RightX);
        BinaryPrimitives.WriteInt16LittleEndian(b.AsSpan(26), s.RightY);
        b[28] = s.LeftTrigger; b[29] = s.RightTrigger;
        AppendTag(b, key);
        return b;
    }

    public static byte[] EncodeHeartbeat(uint sessionId, uint sequence, uint timestamp, uint echo, ushort echoDelay, byte[] key)
    {
        var b = new byte[ProtocolConstants.HeartbeatLength];
        WriteHeader(b, ProtocolConstants.TypeHeartbeat, sessionId, sequence);
        BinaryPrimitives.WriteUInt32LittleEndian(b.AsSpan(12), timestamp);
        BinaryPrimitives.WriteUInt32LittleEndian(b.AsSpan(16), echo);
        BinaryPrimitives.WriteUInt16LittleEndian(b.AsSpan(20), echoDelay);
        AppendTag(b, key);
        return b;
    }

    private static void WriteHeader(byte[] b, byte type, uint session, uint seq)
    {
        b[0] = ProtocolConstants.Magic0; b[1] = ProtocolConstants.Magic1; b[2] = ProtocolConstants.Version; b[3] = type;
        BinaryPrimitives.WriteUInt32LittleEndian(b.AsSpan(4), session);
        BinaryPrimitives.WriteUInt32LittleEndian(b.AsSpan(8), seq);
    }

    private static void AppendTag(byte[] b, byte[] key)
    {
        Span<byte> full = stackalloc byte[32];
        HMACSHA256.HashData(key, b.AsSpan(0, b.Length - ProtocolConstants.TagLength), full);
        full[..ProtocolConstants.TagLength].CopyTo(b.AsSpan(b.Length - ProtocolConstants.TagLength));
    }

    /// <summary>
    /// Validation steps 1,2,3,5 of section 4 (length, magic/version, session, tag). The source-IP check (step 4) and
    /// sequence check (step 6, <see cref="SequenceTracker"/>) are applied by the caller before acting on the packet.
    /// </summary>
    public static PacketError TryDecode(ReadOnlySpan<byte> d, uint expectedSession, byte[] key, out DecodedPacket pkt)
    {
        pkt = default;
        if (d.Length != ProtocolConstants.StateLength && d.Length != ProtocolConstants.HeartbeatLength) return PacketError.Length;
        if (d[0] != ProtocolConstants.Magic0 || d[1] != ProtocolConstants.Magic1) return PacketError.Magic;
        if (d[2] != ProtocolConstants.Version) return PacketError.Version;
        byte type = d[3];
        if (type != ProtocolConstants.TypeState && type != ProtocolConstants.TypeHeartbeat) return PacketError.Type;
        int expectedLen = type == ProtocolConstants.TypeState ? ProtocolConstants.StateLength : ProtocolConstants.HeartbeatLength;
        if (d.Length != expectedLen) return PacketError.Length;

        uint session = BinaryPrimitives.ReadUInt32LittleEndian(d[4..]);
        if (session != expectedSession) return PacketError.Session;

        Span<byte> full = stackalloc byte[32];
        HMACSHA256.HashData(key, d[..^ProtocolConstants.TagLength], full);
        if (!CryptographicOperations.FixedTimeEquals(full[..ProtocolConstants.TagLength], d[^ProtocolConstants.TagLength..])) return PacketError.Tag;

        uint seq = BinaryPrimitives.ReadUInt32LittleEndian(d[8..]);
        uint ts = BinaryPrimitives.ReadUInt32LittleEndian(d[12..]);
        if (type == ProtocolConstants.TypeState)
        {
            var st = new GamepadState(
                (GamepadButtons)(BinaryPrimitives.ReadUInt16LittleEndian(d[16..]) & (ushort)GamepadButtons.DefinedMask),
                (DpadFlags)(d[18] & (byte)DpadFlags.DefinedMask),
                BinaryPrimitives.ReadInt16LittleEndian(d[20..]), BinaryPrimitives.ReadInt16LittleEndian(d[22..]),
                BinaryPrimitives.ReadInt16LittleEndian(d[24..]), BinaryPrimitives.ReadInt16LittleEndian(d[26..]),
                d[28], d[29]);
            pkt = new DecodedPacket(type, session, seq, ts, st, 0, 0);
        }
        else
        {
            pkt = new DecodedPacket(type, session, seq, ts, default,
                BinaryPrimitives.ReadUInt32LittleEndian(d[16..]), BinaryPrimitives.ReadUInt16LittleEndian(d[20..]));
        }
        return PacketError.None;
    }
}

/// <summary>Serial-number-arithmetic sequence tracking (wrap-around safe). The first valid sequence is 1 (last starts at 0).</summary>
public sealed class SequenceTracker
{
    private uint _last;
    public uint Last => _last;

    public static bool IsNewer(uint last, uint seq) => (int)(seq - last) > 0;

    public SequenceResult Accept(uint seq)
    {
        if (seq == _last) return SequenceResult.Duplicate;
        if (!IsNewer(_last, seq)) return SequenceResult.OutOfOrder;
        _last = seq;
        return SequenceResult.Accepted;
    }
}
