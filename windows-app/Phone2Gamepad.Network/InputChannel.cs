using System.Net;
using System.Net.Sockets;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace Phone2Gamepad.Network;

/// <summary>UDP input port: receives datagrams (to the SessionManager), sends 2 Hz heartbeats, and drives the staleness tick.</summary>
public sealed class InputChannel : IDisposable
{
    private readonly SessionManager _sessions;
    private readonly ILogger _log;
    private readonly UdpClient _udp;
    private readonly CancellationTokenSource _cts = new();
    private Task? _rxTask, _timerTask;

    public InputChannel(SessionManager sessions, IPAddress bind, int port, ILogger? log = null)
    {
        _sessions = sessions; _log = log ?? NullLogger.Instance;
        _udp = new UdpClient(new IPEndPoint(bind, port));
        Port = ((IPEndPoint)_udp.Client.LocalEndPoint!).Port;
    }

    public int Port { get; }

    public void Start()
    {
        _rxTask = Task.Run(ReceiveLoop);
        _timerTask = Task.Run(TimerLoop);
    }

    private async Task ReceiveLoop()
    {
        while (!_cts.IsCancellationRequested)
        {
            try
            {
                var r = await _udp.ReceiveAsync(_cts.Token);
                _sessions.OnDatagram(r.Buffer, r.RemoteEndPoint);
            }
            catch (OperationCanceledException) { break; }
            catch (ObjectDisposedException) { break; }
            catch (SocketException ex) when (ex.SocketErrorCode == SocketError.ConnectionReset) { /* ICMP port unreachable from a heartbeat target; ignore */ }
            catch (Exception ex) { _log.LogError(ex, "Input receive error"); }
        }
    }

    private async Task TimerLoop()
    {
        using var timer = new PeriodicTimer(TimeSpan.FromMilliseconds(50));
        int n = 0;
        try
        {
            while (await timer.WaitForNextTickAsync(_cts.Token))
            {
                try
                {
                    _sessions.Tick();
                    if (++n % 10 == 0 && _sessions.BuildHeartbeat() is { } hb)
                        await _udp.SendAsync(hb.Packet, hb.Packet.Length, hb.Target);
                }
                catch (SocketException) { }
                catch (ObjectDisposedException) { return; }
                catch (Exception ex) { _log.LogError(ex, "Input timer error"); }
            }
        }
        catch (OperationCanceledException) { }
    }

    public void Dispose()
    {
        _cts.Cancel();
        _udp.Dispose();
        try { Task.WaitAll(new[] { _rxTask, _timerTask }.Where(t => t != null).ToArray()!, 1000); } catch { }
        _cts.Dispose();
    }
}
