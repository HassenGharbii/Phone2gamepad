using System.Globalization;
using System.Windows;
using System.Windows.Data;

namespace Phone2Gamepad.Windows;

/// <summary>true → Collapsed, false → Visible.</summary>
public sealed class InverseBoolVisConverter : IValueConverter
{
    public object Convert(object value, Type t, object p, CultureInfo c) => value is true ? Visibility.Collapsed : Visibility.Visible;
    public object ConvertBack(object value, Type t, object p, CultureInfo c) => throw new NotSupportedException();
}

/// <summary>null/empty → Collapsed, otherwise Visible (reversed with ConverterParameter=invert).</summary>
public sealed class NotEmptyVisConverter : IValueConverter
{
    public object Convert(object value, Type t, object p, CultureInfo c)
    {
        bool empty = value is null || value is string { Length: 0 };
        if (p is "invert") empty = !empty;
        return empty ? Visibility.Collapsed : Visibility.Visible;
    }
    public object ConvertBack(object value, Type t, object p, CultureInfo c) => throw new NotSupportedException();
}

/// <summary>Maps a status/level name (Success, Critical, Attention, Warning, Error…) to a theme brush resource.</summary>
public sealed class ToneBrushConverter : IValueConverter
{
    public string Suffix { get; set; } = "";
    public object Convert(object value, Type t, object p, CultureInfo c)
    {
        var key = (value?.ToString()) switch
        {
            "Success" => "Success", "Critical" or "Error" => "Critical", "Attention" or "Warning" => "Warning", "Accent" => "Accent",
            _ => Suffix == "Bg" ? "LayerAlt" : "FgSecondary",
        };
        if (Suffix == "Bg" && key is "Success" or "Critical" or "Warning") key += "Bg";
        return Application.Current.TryFindResource(key) ?? DependencyProperty.UnsetValue;
    }
    public object ConvertBack(object value, Type t, object p, CultureInfo c) => throw new NotSupportedException();
}

/// <summary>bool → ChecklistState: true = Done, false = the state named by the parameter (Pending/Problem/Info).</summary>
public sealed class ChecklistStateConverter : IValueConverter
{
    public object Convert(object value, Type t, object p, CultureInfo c) =>
        value is true ? Controls.ChecklistState.Done
        : Enum.TryParse<Controls.ChecklistState>(p as string, out var s) ? s : Controls.ChecklistState.Pending;
    public object ConvertBack(object value, Type t, object p, CultureInfo c) => throw new NotSupportedException();
}
