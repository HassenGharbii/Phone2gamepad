using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Phone2Gamepad.Core;
using Phone2Gamepad.Core.Presentation;
using Phone2Gamepad.Windows.ViewModels;
using Phone2Gamepad.Windows.Views;

namespace Phone2Gamepad.Windows;

public partial class MainWindow : Window
{
    public const double CompactRailBelow = 960;
    private static readonly (double W, double H) PreferredSize = (1100, 720);
    private static readonly (double W, double H) MinSize = (720, 520);

    private readonly MainViewModel _vm;
    private readonly AppSettings _settings;
    private readonly Func<UserControl>[] _factories =
        { () => new HomeView(), () => new ControllerView(), () => new PhonesView(), () => new MappingView(), () => new DiagnosticsView(), () => new SettingsView() };
    private readonly UserControl?[] _pages = new UserControl?[6];

    /// <summary>Set by the app: true if closing should hide to the tray instead of exiting.</summary>
    public Func<bool> HideOnClose { get; set; } = () => false;
    public bool ForceClose { get; set; }

    public MainWindow(MainViewModel vm, AppSettings settings)
    {
        InitializeComponent();
        _vm = vm; _settings = settings;
        DataContext = vm;
        ShowPage(vm.SelectedPage);
        vm.PropertyChanged += (_, e) => { if (e.PropertyName == nameof(MainViewModel.SelectedPage)) ShowPage(vm.SelectedPage); };
        ((FrameworkElement)Content).SizeChanged += (_, e) => ApplyRail(e.NewSize.Width); // layout units, so text scaling is respected
        SourceInitialized += (_, _) => RestorePlacement();
    }

    private void ShowPage(int i)
    {
        _pages[i] ??= _factories[i]();
        PageHost.Content = _pages[i];
    }

    /// <summary>Icon-only navigation rail on narrow windows; labels come back when there is room.</summary>
    private void ApplyRail(double width)
    {
        bool compact = width < CompactRailBelow;
        RailColumn.Width = new GridLength(compact ? 64 : 232);
        BrandText.Visibility = compact ? Visibility.Collapsed : Visibility.Visible;
        ServerText.Visibility = compact ? Visibility.Collapsed : Visibility.Visible;
        ServerButton.Content = compact ? "" : null;
        if (compact) { ServerButton.FontFamily = (FontFamily)FindResource("IconFont"); ServerButton.Padding = new Thickness(0, 6, 0, 6); }
        else { ServerButton.ClearValue(FontFamilyProperty); ServerButton.ClearValue(PaddingProperty); ServerButton.SetBinding(ContentProperty, nameof(MainViewModel.ServerToggleText)); }
        foreach (var label in FindLabels(Nav)) label.Visibility = compact ? Visibility.Collapsed : Visibility.Visible;
    }

    private static IEnumerable<TextBlock> FindLabels(DependencyObject root)
    {
        for (int i = 0; i < VisualTreeHelper.GetChildrenCount(root); i++)
        {
            var c = VisualTreeHelper.GetChild(root, i);
            if (c is TextBlock { Tag: "NavLabel" } t) yield return t;
            foreach (var x in FindLabels(c)) yield return x;
        }
    }

    // ------------------------------------------------------------------ placement (remembered, always kept on a visible work area)
    private void RestorePlacement()
    {
        var b = _settings.Window;
        UiRect? saved = b == null ? null : new UiRect(b.Left, b.Top, b.Width, b.Height);
        var r = WindowPlacement.Resolve(saved, WorkAreasInDips(), PreferredSize, MinSize);
        Left = r.Left; Top = r.Top; Width = r.Width; Height = r.Height;
        if (b?.Maximized == true) WindowState = WindowState.Maximized;
        Dispatcher.BeginInvoke(() => ApplyRail(((FrameworkElement)Content).ActualWidth));
    }

    private IReadOnlyList<UiRect> WorkAreasInDips()
    {
        var dpi = VisualTreeHelper.GetDpi(this);
        return System.Windows.Forms.Screen.AllScreens
            .OrderByDescending(s => s.Primary)
            .Select(s => new UiRect(s.WorkingArea.Left / dpi.DpiScaleX, s.WorkingArea.Top / dpi.DpiScaleY, s.WorkingArea.Width / dpi.DpiScaleX, s.WorkingArea.Height / dpi.DpiScaleY))
            .ToList();
    }

    public void SavePlacement()
    {
        var r = WindowState == WindowState.Normal ? new Rect(Left, Top, ActualWidth, ActualHeight) : RestoreBounds;
        if (r.IsEmpty || r.Width <= 0) return;
        _settings.Window = new WindowBounds { Left = r.Left, Top = r.Top, Width = r.Width, Height = r.Height, Maximized = WindowState == WindowState.Maximized };
        _vm.SaveSettings();
    }

    protected override void OnClosing(CancelEventArgs e)
    {
        SavePlacement();
        if (!ForceClose && HideOnClose())
        {
            e.Cancel = true;
            Hide();
            return;
        }
        base.OnClosing(e);
    }
}
