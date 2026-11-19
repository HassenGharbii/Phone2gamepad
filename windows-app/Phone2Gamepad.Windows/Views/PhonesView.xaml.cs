using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;

namespace Phone2Gamepad.Windows.Views;

public partial class PhonesView : UserControl
{
    public PhonesView() => InitializeComponent();

    /// <summary>Put the caret in the rename box as soon as it appears.</summary>
    private void EditBox_IsVisibleChanged(object sender, DependencyPropertyChangedEventArgs e)
    {
        if (sender is TextBox tb && tb.IsVisible)
            Dispatcher.BeginInvoke(() => { tb.Focus(); Keyboard.Focus(tb); tb.SelectAll(); }, System.Windows.Threading.DispatcherPriority.Input);
    }
}
