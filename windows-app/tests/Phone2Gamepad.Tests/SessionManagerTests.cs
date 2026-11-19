using System.Net;
using Phone2Gamepad.Core;
using Phone2Gamepad.Network;
using Phone2Gamepad.VirtualGamepad;

namespace Phone2Gamepad.Tests;

public class SessionManagerTests
{
    private static readonly IPAddress Peer = IPAddress.Parse("192.168.1.50");
    private static readonly IPEndPoint PeerEp = new(Peer, 40000);
    private readonly byte[] _kc = Enumerable.Range(1, 32).Select(i => (byte)i).ToArray();
    private readonly byte[] _ks = Enumerable.Range(101, 32).Select(i => (byte)i).ToArray();
    private const uint Sid = 0xABCD0001;

    private readonly MockVirtualGamepad _pad = new();
    private readonly ManualTimeProvider _time = new();
    private readonly ServerStats _stats = new();
    private readonly SessionManager _sm;
    private readonly ActiveSession _s;
    private readonly List<string> _ended = new();

    public SessionManagerTests()
    {
        _pad.Connect();
        _sm = new SessionManager(_pad, MappingProfile.CreateDefault, _stats, time: _time);
        _sm.SessionEnded += e => _ended.Add(e.Reason);
        Assert.Equal(StartResult.Started, _sm.TryStart("dev", "Phone", Peer, _kc, _ks, Sid, _ => { }, out var s));
        _s = s!;
    }

    private byte[] State(uint seq, GamepadState st, uint sid = Sid) => PacketCodec.EncodeState(sid, seq, 0, st, _kc);
    private static GamepadState A => new(GamepadButtons.A, 0, 0, 0, 0, 0, 0, 0);

    [Fact]
    public void Valid_state_is_applied_to_the_gamepad()
    {
        _sm.OnDatagram(State(1, new GamepadState(GamepadButtons.A | GamepadButtons.B, DpadFlags.Up, 100, 200, -300, -400, 10, 20)), PeerEp);
        Assert.Equal(new GamepadState(GamepadButtons.A | GamepadButtons.B, DpadFlags.Up, 100, 200, -300, -400, 10, 20), _pad.Current);
        Assert.Equal(1, _stats.StatePackets);
    }

    [Fact]
    public void Duplicate_and_old_sequences_are_dropped_and_counted()
    {
        _sm.OnDatagram(State(5, A), PeerEp);
        var other = new GamepadState(GamepadButtons.B, 0, 0, 0, 0, 0, 0, 0);
        _sm.OnDatagram(State(5, other), PeerEp);   // duplicate seq (replay with changed payload would fail tag in real life; here valid tag)
        _sm.OnDatagram(State(4, other), PeerEp);   // out of order
        Assert.Equal(A, _pad.Current);
        Assert.Equal(1, _stats.Duplicates);
        Assert.Equal(1, _stats.OutOfOrder);
    }

    [Fact]
    public void Exact_replay_of_a_captured_packet_does_nothing()
    {
        var pkt = State(1, A);
        _sm.OnDatagram(pkt, PeerEp);
        _sm.OnDatagram(State(2, GamepadState.Neutral), PeerEp);
        _sm.OnDatagram(pkt, PeerEp); // attacker replays the old "A pressed"
        Assert.True(_pad.Current.IsNeutral);
    }

    [Fact]
    public void Sequence_wraparound_is_accepted()
    {
        foreach (var seq in new uint[] { 1, 0x70000000, 0xE0000000, 0xFFFFFFFF }) // each hop < 2^31, so every step is "newer"
            _sm.OnDatagram(State(seq, GamepadState.Neutral), PeerEp);
        _sm.OnDatagram(State(0, A), PeerEp);    // wraps past 2^32
        Assert.Equal(A, _pad.Current);
        Assert.Equal(0, _stats.OutOfOrder);
    }

    [Fact]
    public void Wrong_session_bad_tag_malformed_and_wrong_source_are_rejected()
    {
        _sm.OnDatagram(State(1, A, sid: Sid + 1), PeerEp);
        Assert.Equal(1, _stats.Invalid(PacketError.Session));
        var bad = State(1, A); bad[^1] ^= 1;
        _sm.OnDatagram(bad, PeerEp);
        Assert.Equal(1, _stats.Invalid(PacketError.Tag));
        _sm.OnDatagram(new byte[10], PeerEp);
        Assert.Equal(1, _stats.Invalid(PacketError.Length));
        _sm.OnDatagram(State(1, A), new IPEndPoint(IPAddress.Parse("192.168.1.99"), 40000)); // right key, wrong host
        Assert.Equal(1, _stats.BadSource);
        Assert.True(_pad.Current.IsNeutral);
        Assert.Equal(4, _stats.InvalidTotal);
    }

    [Fact]
    public void Packet_signed_with_the_server_to_client_key_is_rejected()
    {
        _sm.OnDatagram(PacketCodec.EncodeState(Sid, 1, 0, A, _ks), PeerEp);
        Assert.True(_pad.Current.IsNeutral);
        Assert.Equal(1, _stats.Invalid(PacketError.Tag));
    }

    [Fact]
    public void Stale_input_neutralizes_after_500ms_and_session_ends_after_5s()
    {
        _sm.OnDatagram(State(1, A), PeerEp);
        _time.Advance(TimeSpan.FromMilliseconds(499)); _sm.Tick();
        Assert.Equal(A, _pad.Current);
        _time.Advance(TimeSpan.FromMilliseconds(2)); _sm.Tick();
        Assert.True(_pad.Current.IsNeutral);
        Assert.True(_sm.IsNeutralized);
        Assert.NotNull(_sm.Current);
        _time.Advance(TimeSpan.FromMilliseconds(4400)); _sm.Tick();
        Assert.NotNull(_sm.Current);
        _time.Advance(TimeSpan.FromMilliseconds(200)); _sm.Tick();
        Assert.Null(_sm.Current);
        Assert.Equal(new[] { "timeout" }, _ended);
    }

    [Fact]
    public void Input_resumes_after_a_stale_gap()
    {
        _sm.OnDatagram(State(1, A), PeerEp);
        _time.Advance(TimeSpan.FromSeconds(1)); _sm.Tick();
        Assert.True(_pad.Current.IsNeutral);
        _sm.OnDatagram(State(2, A), PeerEp);
        Assert.Equal(A, _pad.Current);
    }

    [Fact]
    public void Ending_the_session_neutralizes_and_old_packets_no_longer_validate()
    {
        _sm.OnDatagram(State(1, A), PeerEp);
        _sm.End(_s, "test");
        Assert.True(_pad.Current.IsNeutral);
        _sm.OnDatagram(State(2, A), PeerEp);
        Assert.True(_pad.Current.IsNeutral);
        Assert.Equal(1, _stats.NoSession);
    }

    [Fact]
    public void A_second_device_gets_busy_but_the_same_device_replaces_its_old_session()
    {
        Assert.Equal(StartResult.Busy, _sm.TryStart("other", "Other", Peer, _kc, _ks, 77, _ => { }, out _));
        _sm.OnDatagram(State(1, A), PeerEp);
        Assert.Equal(StartResult.Started, _sm.TryStart("dev", "Phone", Peer, _kc, _ks, Sid + 5, _ => { }, out var s2));
        Assert.True(_pad.Current.IsNeutral);          // old session's buttons released
        Assert.Contains("replaced", _ended);
        _sm.OnDatagram(State(2, A, sid: Sid), PeerEp); // old session id is dead
        Assert.True(_pad.Current.IsNeutral);
        Assert.Equal(Sid + 5, s2!.Id);
    }

    [Fact]
    public void Heartbeat_echo_yields_rtt_and_heartbeats_are_built_for_the_known_endpoint()
    {
        Assert.Null(_sm.BuildHeartbeat());             // phone endpoint unknown until first valid packet
        _time.Advance(TimeSpan.FromMilliseconds(1000));
        uint serverTs = _sm.SessionClockMs(_s);        // 1000
        _time.Advance(TimeSpan.FromMilliseconds(30));  // 30 ms later the phone's heartbeat arrives, having held our ts for 10 ms
        var hb = PacketCodec.EncodeHeartbeat(Sid, 1, 555, serverTs, 10, _kc);
        _sm.OnDatagram(hb, PeerEp);
        Assert.Equal(20.0, _s.RttMs);                  // 30 - 10
        var built = _sm.BuildHeartbeat();
        Assert.NotNull(built);
        Assert.Equal(PacketError.None, PacketCodec.TryDecode(built!.Value.Packet, Sid, _ks, out var d));
        Assert.Equal(555u, d.Echo);
        Assert.Equal(PeerEp, built.Value.Target);
    }

    [Fact]
    public void Mapping_profile_is_applied_between_wire_and_driver()
    {
        var sm = new SessionManager(_pad, () => new MappingProfile { InvertLeftY = true, ButtonMap = { [GamepadButtons.A] = GamepadButtons.B } }, _stats, time: _time);
        sm.TryStart("dev", "Phone", Peer, _kc, _ks, Sid, _ => { }, out _);
        sm.OnDatagram(State(1, new GamepadState(GamepadButtons.A, 0, 0, 10000, 0, 0, 0, 0)), PeerEp);
        Assert.Equal(GamepadButtons.B, _pad.Current.Buttons);
        Assert.Equal(-10000, _pad.Current.LeftY);
    }
}
