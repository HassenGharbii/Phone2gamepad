namespace Phone2Gamepad.Core.Presentation;

public enum StatusTone { Neutral, Success, Attention, Critical }

/// <summary>The single next action the home screen offers for a given state.</summary>
public enum HomeAction { None, StartServer, OpenPairing, DriverHelp, RetryDriver }

public sealed record HomeStatus(StatusTone Tone, string Title, string Detail, HomeAction Action, string ActionText);

/// <summary>Inputs to <see cref="HomeStatusRules.Compute"/>: plain values so the rules are unit-testable without a server.</summary>
public sealed record HomeFacts(
    bool DriverMissing, bool DriverError, bool ServerRunning, string? ServerError,
    string? ConnectedPhone, bool InputStale, bool PairingOpen, int PairedPhones);

public static class HomeStatusRules
{
    /// <summary>Priority: driver problems, server errors, server stopped, playing, pairing, waiting.</summary>
    public static HomeStatus Compute(HomeFacts f)
    {
        if (f.DriverMissing)
            return new(StatusTone.Critical, "Driver needed",
                "Install the ViGEmBus driver so Windows can see a virtual Xbox controller. The app never installs drivers by itself.",
                HomeAction.DriverHelp, "How to install");
        if (f.DriverError)
            return new(StatusTone.Critical, "Virtual controller unavailable",
                "The driver reported an error. Try again; if it keeps failing, restart the PC.", HomeAction.RetryDriver, "Try again");
        if (!string.IsNullOrEmpty(f.ServerError))
            return new(StatusTone.Critical, "Server couldn't start", f.ServerError!, HomeAction.StartServer, "Try again");
        if (!f.ServerRunning)
            return new(StatusTone.Neutral, "Server is off", "Start the server so your phone can find this PC.", HomeAction.StartServer, "Start server");
        if (f.ConnectedPhone is { } phone)
            return f.InputStale
                ? new(StatusTone.Attention, $"Connected — {phone}", "No input for a moment, so every control was released.", HomeAction.None, "")
                : new(StatusTone.Success, $"Playing — {phone}", "Your phone is driving the virtual Xbox controller.", HomeAction.None, "");
        if (f.PairingOpen)
            return new(StatusTone.Neutral, "Waiting for your phone", "Enter the PIN in the Phone2Gamepad app on your phone, then approve it here.", HomeAction.None, "");
        if (f.PairedPhones == 0)
            return new(StatusTone.Neutral, "Ready to pair", "Pair your phone once; after that it connects by itself.", HomeAction.OpenPairing, "Pair a phone");
        return new(StatusTone.Neutral, "Ready — waiting for your phone", "Open Phone2Gamepad on your phone and choose this PC.", HomeAction.OpenPairing, "Pair another phone");
    }
}
