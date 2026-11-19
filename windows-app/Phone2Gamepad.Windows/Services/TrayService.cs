using System.Drawing;
using System.Windows.Forms;

namespace Phone2Gamepad.Windows.Services;

/// <summary>System tray icon with Open / Start-Stop / Exit.</summary>
public sealed class TrayService : IDisposable
{
    private readonly NotifyIcon _icon;
    private readonly ToolStripMenuItem _toggle;

    public TrayService(Action open, Action toggleServer, Action exit)
    {
        _toggle = new ToolStripMenuItem("Start server", null, (_, _) => toggleServer());
        var menu = new ContextMenuStrip();
        menu.Items.Add(new ToolStripMenuItem("Open dashboard", null, (_, _) => open()));
        menu.Items.Add(_toggle);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(new ToolStripMenuItem("Exit", null, (_, _) => exit()));
        _icon = new NotifyIcon { Icon = SystemIcons.Application, Text = "Phone2Gamepad", ContextMenuStrip = menu, Visible = true };
        _icon.DoubleClick += (_, _) => open();
    }

    public void SetServerRunning(bool running)
    {
        _toggle.Text = running ? "Stop server" : "Start server";
        _icon.Text = running ? "Phone2Gamepad - server running" : "Phone2Gamepad - server stopped";
    }

    public void Notify(string title, string text) => _icon.ShowBalloonTip(3000, title, text, ToolTipIcon.Info);

    public void Dispose() { _icon.Visible = false; _icon.Dispose(); }
}
