using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using Phone2Gamepad.Core.Presentation;
using Phone2Gamepad.Network;

namespace Phone2Gamepad.Windows.Services;

/// <summary>Collects this PC's private IPv4 addresses with the adapter facts used to rank them for display.</summary>
public static class NetworkInfo
{
    public static IReadOnlyList<AdapterAddress> Addresses()
    {
        var list = new List<AdapterAddress>();
        try
        {
            foreach (var n in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (n.OperationalStatus != OperationalStatus.Up || n.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
                var props = n.GetIPProperties();
                bool gw = props.GatewayAddresses.Any(g => g.Address.AddressFamily == AddressFamily.InterNetwork && !g.Address.Equals(IPAddress.Any));
                foreach (var u in props.UnicastAddresses)
                {
                    if (u.Address.AddressFamily != AddressFamily.InterNetwork || !NetUtil.IsPrivateOrLoopback(u.Address)) continue;
                    list.Add(new AdapterAddress(u.Address, n.Name, n.Description, n.NetworkInterfaceType == NetworkInterfaceType.Wireless80211, gw));
                }
            }
        }
        catch (NetworkInformationException) { }
        return AddressRanking.Rank(list);
    }
}
