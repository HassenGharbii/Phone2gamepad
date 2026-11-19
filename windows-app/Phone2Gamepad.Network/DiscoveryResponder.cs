using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using System.Text;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;
using Phone2Gamepad.Core;

namespace Phone2Gamepad.Network;

/// <summary>Answers UDP discovery requests (PROTOCOL.md section 2). Carries no secrets; only answers private-network senders.</summary>
public sealed class DiscoveryResponder : IDisposable
{
    private readonly UdpClient _udp;
    private readonly string _name;
    private readonly int _tcpPort, _udpPort;
    private readonly Func<bool> _pairingOpen;
    private readonly bool _privateOnly;
    private readonly ILogger _log;
    private readonly CancellationTokenSource _cts = new();
    private Task? _task;
    private int _windowCount; private long _windowStart;

    public DiscoveryResponder(IPAddress bind, int port, string name, int tcpPort, int udpPort, Func<bool> pairingOpen, bool privateOnly, ILogger? log = null)
    {
        _name = name.Length > 63 ? name[..63] : name; _tcpPort = tcpPort; _udpPort = udpPort; _pairingOpen = pairingOpen; _privateOnly = privateOnly;
        _log = log ?? NullLogger.Instance;
        _udp = new UdpClient(AddressFamily.InterNetwork) { EnableBroadcast = true };
        _udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        _udp.Client.Bind(new IPEndPoint(bind, port));
        Port = ((IPEndPoint)_udp.Client.LocalEndPoint!).Port;
    }

    public int Port { get; }

    public void Start() => _task = Task.Run(Loop);

    private async Task Loop()
    {
        while (!_cts.IsCancellationRequested)
        {
            try
            {
                var r = await _udp.ReceiveAsync(_cts.Token);
                var d = r.Buffer;
                if (d.Length != ProtocolConstants.DiscoveryRequestLength || d[0] != ProtocolConstants.Magic0 || d[1] != ProtocolConstants.Magic1
                    || d[2] != ProtocolConstants.Version || d[3] != ProtocolConstants.TypeDiscoveryRequest) continue;
                if (_privateOnly && !NetUtil.IsPrivateOrLoopback(r.RemoteEndPoint.Address)) continue;
                if (!AllowResponse()) continue;
                var resp = BuildResponse(BinaryPrimitives.ReadUInt32LittleEndian(d.AsSpan(4)));
                await _udp.SendAsync(resp, resp.Length, r.RemoteEndPoint);
                _log.LogDebug("Discovery request answered");
            }
            catch (OperationCanceledException) { break; }
            catch (ObjectDisposedException) { break; }
            catch (SocketException) { }
            catch (Exception ex) { _log.LogError(ex, "Discovery error"); }
        }
    }

    /// <summary>Global cap of 20 responses/second so the responder cannot be used as an amplifier.</summary>
    private bool AllowResponse()
    {
        long now = Environment.TickCount64;
        if (now - _windowStart >= 1000) { _windowStart = now; _windowCount = 0; }
        return ++_windowCount <= 20;
    }

    internal byte[] BuildResponse(uint nonce)
    {
        var name = Encoding.UTF8.GetBytes(_name);
        if (name.Length > 63) name = name[..63];
        var b = new byte[14 + name.Length];
        b[0] = ProtocolConstants.Magic0; b[1] = ProtocolConstants.Magic1; b[2] = ProtocolConstants.Version; b[3] = ProtocolConstants.TypeDiscoveryResponse;
        BinaryPrimitives.WriteUInt32LittleEndian(b.AsSpan(4), nonce);
        BinaryPrimitives.WriteUInt16LittleEndian(b.AsSpan(8), (ushort)_tcpPort);
        BinaryPrimitives.WriteUInt16LittleEndian(b.AsSpan(10), (ushort)_udpPort);
        b[12] = (byte)(_pairingOpen() ? 1 : 0);
        b[13] = (byte)name.Length;
        name.CopyTo(b, 14);
        return b;
    }

    public void Dispose()
    {
        _cts.Cancel();
        _udp.Dispose();
        try { _task?.Wait(1000); } catch { }
        _cts.Dispose();
    }
}

public static class NetUtil
{
    /// <summary>RFC1918, link-local, CGNAT-free: loopback, 10/8, 172.16/12, 192.168/16, 169.254/16, IPv6 loopback/link-local/ULA.</summary>
    public static bool IsPrivateOrLoopback(IPAddress ip)
    {
        if (ip.IsIPv4MappedToIPv6) ip = ip.MapToIPv4();
        if (IPAddress.IsLoopback(ip)) return true;
        if (ip.AddressFamily == AddressFamily.InterNetwork)
        {
            var b = ip.GetAddressBytes();
            return b[0] == 10 || (b[0] == 172 && b[1] is >= 16 and <= 31) || (b[0] == 192 && b[1] == 168) || (b[0] == 169 && b[1] == 254);
        }
        if (ip.AddressFamily == AddressFamily.InterNetworkV6)
            return ip.IsIPv6LinkLocal || (ip.GetAddressBytes()[0] & 0xFE) == 0xFC;
        return false;
    }

    public static IEnumerable<IPAddress> LocalIPv4Addresses() =>
        System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces()
            .Where(n => n.OperationalStatus == System.Net.NetworkInformation.OperationalStatus.Up
                        && n.NetworkInterfaceType != System.Net.NetworkInformation.NetworkInterfaceType.Loopback)
            .SelectMany(n => n.GetIPProperties().UnicastAddresses)
            .Select(u => u.Address)
            .Where(a => a.AddressFamily == AddressFamily.InterNetwork && IsPrivateOrLoopback(a));
}
