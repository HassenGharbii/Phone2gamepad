using System.Net;
using System.Net.Sockets;

namespace Phone2Gamepad.Core.Presentation;

public sealed record AdapterAddress(IPAddress Address, string AdapterName, string AdapterDescription, bool IsWireless, bool HasGateway);

/// <summary>Orders local addresses so the one a phone on the same Wi-Fi can reach comes first.</summary>
public static class AddressRanking
{
    private static readonly string[] VirtualHints =
        { "vEthernet", "Hyper-V", "WSL", "VirtualBox", "VMware", "Virtual", "TAP-", "Tunnel", "Loopback", "Bluetooth", "ZeroTier", "Tailscale" };

    public static bool LooksVirtual(AdapterAddress a) =>
        VirtualHints.Any(h => a.AdapterName.Contains(h, StringComparison.OrdinalIgnoreCase) || a.AdapterDescription.Contains(h, StringComparison.OrdinalIgnoreCase));

    public static bool IsLinkLocal(IPAddress ip) => ip.AddressFamily == AddressFamily.InterNetwork && ip.GetAddressBytes() is [169, 254, ..];

    /// <summary>Higher is better: a real adapter with a gateway (Wi-Fi first); link-local and virtual adapters last.</summary>
    public static int Score(AdapterAddress a)
    {
        if (IsLinkLocal(a.Address)) return 0;
        int s = 10;
        if (!LooksVirtual(a)) s += 40;
        if (a.HasGateway) s += 30;
        if (a.IsWireless) s += 10;
        if (a.Address.GetAddressBytes() is [192, 168, ..] or [10, ..]) s += 5;
        return s;
    }

    public static IReadOnlyList<AdapterAddress> Rank(IEnumerable<AdapterAddress> all) =>
        all.Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork)
           .OrderByDescending(Score).ThenBy(a => a.Address.ToString(), StringComparer.Ordinal).ToList();

    /// <summary>The address worth showing as "this PC", or null when only link-local/virtual ones exist.</summary>
    public static AdapterAddress? Primary(IEnumerable<AdapterAddress> all) => Rank(all).FirstOrDefault(a => Score(a) >= 50);
}
