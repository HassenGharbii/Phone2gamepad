using System.ComponentModel;
using System.Windows;
using Phone2Gamepad.Windows.ViewModels;

namespace Phone2Gamepad.Windows;

public partial class MainWindow : Window
{
    /// <summary>Set by the app: true if closing should hide to the tray instead of exiting.</summary>
    public Func<bool> HideOnClose { get; set; } = () => false;
    public bool ForceClose { get; set; }

    public MainWindow(MainViewModel vm)
    {
        InitializeComponent();
        DataContext = vm;
    }

    protected override void OnClosing(CancelEventArgs e)
    {
        if (!ForceClose && HideOnClose())
        {
            e.Cancel = true;
            Hide();
            return;
        }
        base.OnClosing(e);
    }
}
