using Microsoft.Win32;

namespace Phone2Gamepad.Windows.Services;

/// <summary>Per-user "start with Windows" via HKCU Run key. No admin rights, no scheduled tasks, removable by the user.</summary>
public static class StartupRegistration
{
    private const string KeyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "Phone2Gamepad";

    public static bool IsEnabled()
    {
        using var k = Registry.CurrentUser.OpenSubKey(KeyPath);
        return k?.GetValue(ValueName) != null;
    }

    public static void Set(bool enabled)
    {
        using var k = Registry.CurrentUser.CreateSubKey(KeyPath);
        if (enabled)
        {
            var exe = Environment.ProcessPath ?? throw new InvalidOperationException("Cannot determine executable path.");
            k.SetValue(ValueName, $"\"{exe}\" --minimized");
        }
        else k.DeleteValue(ValueName, throwOnMissingValue: false);
    }
}
