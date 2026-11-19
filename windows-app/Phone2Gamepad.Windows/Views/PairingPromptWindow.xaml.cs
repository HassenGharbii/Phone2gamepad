using System.Windows;

namespace Phone2Gamepad.Windows.Views;

/// <summary>
/// Approval for a phone that already proved the PIN. Security defaults: keyboard focus starts on "Don't allow", Esc and
/// closing the window deny, and the prompt closes (denied) if the phone gives up waiting.
/// </summary>
public partial class PairingPromptWindow : Window
{
    public static readonly TimeSpan ArmDelay = TimeSpan.FromSeconds(1.5);
    private bool _decided;

    public PairingPromptWindow(string phoneName, string phoneIp)
    {
        InitializeComponent();
        PhoneName.Text = phoneName;
        PhoneIp.Text = $"From {phoneIp} on your local network";
        // Guard against an accidental click: the prompt can pop up under the pointer, so Allow is only enabled after a moment.
        AllowButton.IsEnabled = false;
        var arm = new System.Windows.Threading.DispatcherTimer { Interval = ArmDelay };
        arm.Tick += (_, _) => { arm.Stop(); AllowButton.IsEnabled = true; };
        Loaded += (_, _) => { DenyButton.Focus(); Activate(); arm.Start(); };
    }

    /// <summary>How the decision was made (mouse, keyboard, automation) for the audit log.</summary>
    public string DecidedVia { get; private set; } = "none";

    private void Allow_Click(object sender, RoutedEventArgs e) { DecidedVia = InputSource(); _decided = true; DialogResult = true; }

    private static string InputSource() => System.Windows.Input.InputManager.Current.MostRecentInputDevice switch
    {
        System.Windows.Input.MouseDevice => "mouse",
        System.Windows.Input.KeyboardDevice => "keyboard",
        System.Windows.Input.StylusDevice => "touch/pen",
        null => "automation",
        var d => d.GetType().Name,
    };
    private void Deny_Click(object sender, RoutedEventArgs e) { DecidedVia = InputSource(); _decided = true; DialogResult = false; }

    /// <summary>Called when the phone stopped waiting: close without approving.</summary>
    public void Abandon()
    {
        if (_decided || !IsLoaded) return;
        _decided = true;
        DecidedVia = "phone stopped waiting";
        try { DialogResult = false; } catch (InvalidOperationException) { Close(); }
    }
}
