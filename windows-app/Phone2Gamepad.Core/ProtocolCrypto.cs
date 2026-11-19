using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;

namespace Phone2Gamepad.Core;

/// <summary>Standard primitives only: ECDH P-256, HKDF-SHA256, HMAC-SHA256 (PROTOCOL.md section 6).</summary>
public static class ProtocolCrypto
{
    public const int PublicKeyLength = 65;
    public const int NonceLength = 16;

    public static byte[] Hkdf(byte[] ikm, byte[] salt, string info, int length) =>
        Hkdf(ikm, salt, Encoding.ASCII.GetBytes(info), length);

    public static byte[] Hkdf(byte[] ikm, byte[] salt, byte[] info, int length) =>
        HKDF.DeriveKey(HashAlgorithmName.SHA256, ikm, length, salt, info);

    public static byte[] Hmac(byte[] key, params byte[][] parts) => HMACSHA256.HashData(key, Concat(parts));

    public static byte[] Concat(params byte[][] parts)
    {
        var r = new byte[parts.Sum(p => p.Length)];
        int o = 0;
        foreach (var p in parts) { Buffer.BlockCopy(p, 0, r, o, p.Length); o += p.Length; }
        return r;
    }

    public static byte[] RandomBytes(int n) => RandomNumberGenerator.GetBytes(n);

    // ---- ECDH -------------------------------------------------------------------------------
    public static ECDiffieHellman CreateEcdh() => ECDiffieHellman.Create(ECCurve.NamedCurves.nistP256);

    /// <summary>Uncompressed X9.62 point: 0x04 || X(32) || Y(32).</summary>
    public static byte[] ExportPublic(ECDiffieHellman ecdh)
    {
        var q = ecdh.ExportParameters(false).Q;
        var r = new byte[PublicKeyLength];
        r[0] = 4;
        Buffer.BlockCopy(q.X!, 0, r, 1 + (32 - q.X!.Length), q.X!.Length);
        Buffer.BlockCopy(q.Y!, 0, r, 33 + (32 - q.Y!.Length), q.Y!.Length);
        return r;
    }

    /// <summary>Raw 32-byte X coordinate of the shared point. Throws CryptographicException on malformed peer keys.</summary>
    public static byte[] DeriveShared(ECDiffieHellman own, byte[] peerPublic)
    {
        if (peerPublic.Length != PublicKeyLength || peerPublic[0] != 4) throw new CryptographicException("Bad public key encoding.");
        var p = new ECParameters
        {
            Curve = ECCurve.NamedCurves.nistP256,
            Q = new ECPoint { X = peerPublic[1..33], Y = peerPublic[33..65] },
        };
        try
        {
            using var peer = ECDiffieHellman.Create(p); // validates the point is on the curve
            return own.DeriveRawSecretAgreement(peer.PublicKey);
        }
        catch (Exception ex) when (ex is PlatformNotSupportedException or ArgumentException)
        {
            throw new CryptographicException("Invalid peer public key.", ex);
        }
    }

    // ---- Pairing (6.1) ----------------------------------------------------------------------
    public static byte[] ConfirmKey(byte[] shared, string pin, byte[] nonceC, byte[] nonceS) =>
        Hkdf(Concat(shared, Encoding.ASCII.GetBytes(pin)), Concat(nonceC, nonceS), "P2G confirm v1", 32);

    public static byte[] PairProof(byte[] confirmKey, bool fromClient, byte[] pubC, byte[] pubS) =>
        Hmac(confirmKey, Encoding.ASCII.GetBytes(fromClient ? "client" : "server"), pubC, pubS);

    public static byte[] DeviceSecret(byte[] shared, byte[] nonceC, byte[] nonceS) =>
        Hkdf(shared, Concat(nonceC, nonceS), "P2G device secret v1", 32);

    // ---- Session (6.2) ----------------------------------------------------------------------
    public static byte[] SessionMacClient(byte[] deviceSecret, byte[] nonceC, byte[] nonceS) =>
        Hmac(deviceSecret, Encoding.ASCII.GetBytes("auth-c"), nonceC, nonceS);

    public static byte[] SessionMacServer(byte[] deviceSecret, byte[] nonceC, byte[] nonceS, uint sessionId) =>
        Hmac(deviceSecret, Encoding.ASCII.GetBytes("auth-s"), nonceC, nonceS, U32(sessionId));

    public static (byte[] ClientToServer, byte[] ServerToClient) SessionKeys(byte[] deviceSecret, byte[] nonceC, byte[] nonceS, uint sessionId)
    {
        var okm = Hkdf(deviceSecret, Concat(nonceC, nonceS), Concat(Encoding.ASCII.GetBytes("P2G session v1"), U32(sessionId)), 64);
        return (okm[..32], okm[32..]);
    }

    public static byte[] U32(uint v) { var b = new byte[4]; BinaryPrimitives.WriteUInt32LittleEndian(b, v); return b; }

    public static bool FixedEquals(byte[] a, byte[] b) => CryptographicOperations.FixedTimeEquals(a, b);
}
