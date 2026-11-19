using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
using Microsoft.Win32;

namespace Phone2Gamepad.Windows.Services;

public enum EffectiveTheme { Dark, Light, HighContrast }

/// <summary>
/// Swaps the color token dictionary (System / Dark / Light, plus Windows high contrast) at runtime and keeps every
/// window's title bar in step via DWM. No system setting is ever changed.
/// </summary>
public sealed class ThemeService : IDisposable
{
    private readonly ResourceDictionary _appResources;
    private ResourceDictionary? _current;
    private string _preference = "System";

    public EffectiveTheme Effective { get; private set; } = EffectiveTheme.Dark;
    public event Action? Changed;

    public ThemeService(ResourceDictionary appResources)
    {
        _appResources = appResources;
        _current = appResources.MergedDictionaries.FirstOrDefault(d => d.Source?.OriginalString.Contains("Colors.") == true);
        SystemEvents.UserPreferenceChanged += OnSystemPreferenceChanged;
        // Every window (dialogs included) gets a caption that matches the theme.
        EventManager.RegisterClassHandler(typeof(Window), FrameworkElement.LoadedEvent, new RoutedEventHandler((s, _) => { if (s is Window w) ApplyTitleBar(w); }));
        SystemParameters.StaticPropertyChanged += OnSystemParameterChanged;
    }

    public void Apply(string preference)
    {
        _preference = preference;
        var effective = SystemParameters.HighContrast ? EffectiveTheme.HighContrast
            : preference switch { "Dark" => EffectiveTheme.Dark, "Light" => EffectiveTheme.Light, _ => SystemUsesLightTheme() ? EffectiveTheme.Light : EffectiveTheme.Dark };
        var uri = effective switch
        {
            EffectiveTheme.Light => "Themes/Colors.Light.xaml",
            EffectiveTheme.HighContrast => "Themes/Colors.HighContrast.xaml",
            _ => "Themes/Colors.Dark.xaml",
        };
        var dict = new ResourceDictionary { Source = new Uri($"pack://application:,,,/Phone2Gamepad;component/{uri}") };
        if (_current != null) _appResources.MergedDictionaries.Remove(_current);
        _appResources.MergedDictionaries.Insert(0, dict);
        _current = dict;
        Effective = effective;
        foreach (Window w in Application.Current.Windows) ApplyTitleBar(w);
        Changed?.Invoke();
    }

    /// <summary>Dark/light caption that matches the page background (Windows 11; silently ignored on older builds).</summary>
    public void ApplyTitleBar(Window w)
    {
        var hwnd = new WindowInteropHelper(w).Handle;
        if (hwnd == IntPtr.Zero) { w.SourceInitialized -= OnSourceInitialized; w.SourceInitialized += OnSourceInitialized; return; }
        int dark = Effective == EffectiveTheme.Dark ? 1 : 0;
        _ = DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, ref dark, sizeof(int));
        if (Effective != EffectiveTheme.HighContrast && _appResources["Bg"] is SolidColorBrush bg)
        {
            int colorref = bg.Color.R | (bg.Color.G << 8) | (bg.Color.B << 16);
            _ = DwmSetWindowAttribute(hwnd, DWMWA_CAPTION_COLOR, ref colorref, sizeof(int));
        }
        else
        {
            int def = unchecked((int)0xFFFFFFFF); // DWMWA_COLOR_DEFAULT
            _ = DwmSetWindowAttribute(hwnd, DWMWA_CAPTION_COLOR, ref def, sizeof(int));
        }
    }

    private void OnSourceInitialized(object? sender, EventArgs e)
    {
        if (sender is Window w) { w.SourceInitialized -= OnSourceInitialized; ApplyTitleBar(w); }
    }

    public static bool SystemUsesLightTheme()
    {
        try
        {
            using var k = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
            return k?.GetValue("AppsUseLightTheme") is int v && v != 0;
        }
        catch (Exception ex) when (ex is System.Security.SecurityException or UnauthorizedAccessException or System.IO.IOException) { return false; }
    }

    private void OnSystemPreferenceChanged(object sender, UserPreferenceChangedEventArgs e)
    {
        if (e.Category is UserPreferenceCategory.General or UserPreferenceCategory.Color or UserPreferenceCategory.Accessibility or UserPreferenceCategory.VisualStyle)
            Application.Current?.Dispatcher.BeginInvoke(() => Apply(_preference));
    }

    private void OnSystemParameterChanged(object? sender, System.ComponentModel.PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(SystemParameters.HighContrast)) Application.Current?.Dispatcher.BeginInvoke(() => Apply(_preference));
    }

    public void Dispose()
    {
        SystemEvents.UserPreferenceChanged -= OnSystemPreferenceChanged;
        SystemParameters.StaticPropertyChanged -= OnSystemParameterChanged;
    }

    private const int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
    private const int DWMWA_CAPTION_COLOR = 35;
    [DllImport("dwmapi.dll")] private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);
}
