namespace Phone2Gamepad.Core;

/// <summary>Constants from shared/protocol/PROTOCOL.md (v1).</summary>
public static class ProtocolConstants
{
    public const byte Magic0 = 0x50, Magic1 = 0x32;
    public const byte Version = 1;

    public const int DefaultDiscoveryPort = 47820;
    public const int DefaultControlPort = 47821;
    public const int DefaultInputPort = 47822;

    public const int HeaderLength = 12;
    public const int TagLength = 16;
    public const int StateLength = 46;
    public const int HeartbeatLength = 40;
    public const int DiscoveryRequestLength = 8;

    public const byte TypeState = 0x01;
    public const byte TypeHeartbeat = 0x02;
    public const byte TypeDiscoveryRequest = 0x10;
    public const byte TypeDiscoveryResponse = 0x11;

    public const uint NoEcho = 0xFFFFFFFF;
    public const int MaxControlFrame = 16384;

    public static readonly TimeSpan NeutralizeAfter = TimeSpan.FromMilliseconds(500);
    public static readonly TimeSpan TerminateAfter = TimeSpan.FromSeconds(5);
    public static readonly TimeSpan PinLifetime = TimeSpan.FromSeconds(120);
    public const int MaxPinFailuresPerWindow = 5;
}
