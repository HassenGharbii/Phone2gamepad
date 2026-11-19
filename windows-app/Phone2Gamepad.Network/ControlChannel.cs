using System.Buffers.Binary;
using System.Net.Sockets;
using System.Text;
using System.Text.Json.Nodes;
using Phone2Gamepad.Core;

namespace Phone2Gamepad.Network;

public sealed class BadFrameException(string message) : Exception(message);

/// <summary>Length-prefixed (u32 LE) UTF-8 JSON frames over a stream (PROTOCOL.md section 5). Used by server and client.</summary>
public sealed class ControlChannel(Stream stream)
{
    private readonly SemaphoreSlim _writeLock = new(1, 1);

    /// <summary>Reads one frame; returns null on clean end of stream. Throws BadFrameException for malformed frames.</summary>
    public async Task<JsonObject?> ReadAsync(CancellationToken ct)
    {
        var lenBuf = new byte[4];
        int got = await ReadFullAsync(lenBuf, ct);
        if (got == 0) return null;
        if (got < 4) throw new BadFrameException("Truncated frame header.");
        uint len = BinaryPrimitives.ReadUInt32LittleEndian(lenBuf);
        if (len is 0 or > ProtocolConstants.MaxControlFrame) throw new BadFrameException($"Invalid frame length {len}.");
        var body = new byte[len];
        if (await ReadFullAsync(body, ct) < len) throw new BadFrameException("Truncated frame body.");
        try
        {
            if (JsonNode.Parse(body) is JsonObject o && o["t"] is JsonValue v && v.TryGetValue<string>(out _)) return o;
        }
        catch (System.Text.Json.JsonException) { }
        throw new BadFrameException("Frame is not a JSON object with a string 't'.");
    }

    private async Task<int> ReadFullAsync(byte[] buf, CancellationToken ct)
    {
        int total = 0;
        while (total < buf.Length)
        {
            int n = await stream.ReadAsync(buf.AsMemory(total), ct);
            if (n == 0) break;
            total += n;
        }
        return total;
    }

    public async Task WriteAsync(JsonObject msg, CancellationToken ct)
    {
        var body = Encoding.UTF8.GetBytes(msg.ToJsonString());
        var frame = new byte[4 + body.Length];
        BinaryPrimitives.WriteUInt32LittleEndian(frame, (uint)body.Length);
        body.CopyTo(frame, 4);
        await _writeLock.WaitAsync(ct);
        try { await stream.WriteAsync(frame, ct); await stream.FlushAsync(ct); }
        finally { _writeLock.Release(); }
    }

    public static string Type(JsonObject o) => o["t"]!.GetValue<string>();

    public static string? Str(JsonObject o, string key, int maxLen = 256) =>
        o[key] is JsonValue v && v.TryGetValue<string>(out var s) && s.Length <= maxLen ? s : null;

    public static byte[]? Bytes(JsonObject o, string key, int exactLen)
    {
        var s = Str(o, key, 512);
        if (s == null) return null;
        try { var b = Convert.FromBase64String(s); return b.Length == exactLen ? b : null; }
        catch (FormatException) { return null; }
    }

    public static JsonObject Error(string code, string message) => new() { ["t"] = "error", ["code"] = code, ["message"] = message };
}
