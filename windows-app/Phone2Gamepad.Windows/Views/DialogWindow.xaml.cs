using System.Windows;
using Phone2Gamepad.Windows.ViewModels;

namespace Phone2Gamepad.Windows.Views;

/// <summary>Themed yes/no dialog. Focus starts on the safe (secondary) choice; Esc cancels.</summary>
public partial class DialogWindow : Window
{
    public DialogWindow(DialogRequest r)
    {
        InitializeComponent();
        Title = r.Title;
        Heading.Text = r.Title;
        Message.Text = r.Message;
        Primary.Content = r.PrimaryText;
        Primary.Style = (Style)FindResource(r.Destructive ? "DangerButton" : "AccentButton");
        Secondary.Content = r.SecondaryText;
        Loaded += (_, _) => Secondary.Focus();
    }

    private void Primary_Click(object sender, RoutedEventArgs e) => DialogResult = true;

    public static bool Ask(Window? owner, DialogRequest r)
    {
        var d = new DialogWindow(r) { Icon = owner?.Icon };
        if (owner is { IsVisible: true }) d.Owner = owner; else d.WindowStartupLocation = WindowStartupLocation.CenterScreen;
        return d.ShowDialog() == true;
    }
}
