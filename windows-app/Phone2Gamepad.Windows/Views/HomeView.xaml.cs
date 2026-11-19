using System.Windows;
using System.Windows.Controls;

namespace Phone2Gamepad.Windows.Views;

public partial class HomeView : UserControl
{
    public const double TwoColumnMinWidth = 780;

    public HomeView()
    {
        InitializeComponent();
        SizeChanged += (_, e) => ApplyLayout(e.NewSize.Width);
        // When pairing starts, make sure the PIN is on screen.
        DataContextChanged += (_, e) =>
        {
            if (e.NewValue is System.ComponentModel.INotifyPropertyChanged vm)
                vm.PropertyChanged += (_, p) =>
                {
                    if (p.PropertyName == nameof(ViewModels.MainViewModel.PairingOpen) && ((ViewModels.MainViewModel)vm).PairingOpen && !_pinShown)
                    { _pinShown = true; Dispatcher.BeginInvoke(() => PairingCard.BringIntoView(), System.Windows.Threading.DispatcherPriority.Loaded); }
                    else if (p.PropertyName == nameof(ViewModels.MainViewModel.PairingOpen) && !((ViewModels.MainViewModel)vm).PairingOpen) _pinShown = false;
                };
        };
    }

    private bool _pinShown;

    /// <summary>Two columns when there is room; otherwise the side panel stacks under the controller.</summary>
    private void ApplyLayout(double width)
    {
        bool wide = width >= TwoColumnMinWidth;
        SideCol.Width = wide ? new GridLength(320) : new GridLength(0);
        // Narrow: setup and pairing come before the live view, because that's what a new user needs first.
        Grid.SetRow(SidePanel, 1);
        Grid.SetRow(MainPanel, wide ? 1 : 2);
        Grid.SetColumn(SidePanel, 0);
        Grid.SetColumnSpan(SidePanel, wide ? 1 : 2);
        if (wide) Grid.SetColumn(SidePanel, 1);
        MainPanel.Margin = wide ? new Thickness(0, 0, 12, 0) : new Thickness(0);
        Grid.SetColumnSpan(MainPanel, wide ? 1 : 2);
    }
}
