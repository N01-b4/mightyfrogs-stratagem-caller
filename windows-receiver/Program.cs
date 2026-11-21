using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.ComponentModel;
using System.Text;
using System.Text.Json;

var phonePort = GetPort(args, "--phone-port", 38573);
var discoveryPort = GetPort(args, "--discovery-port", 38574);
using var shutdown = new CancellationTokenSource();
Console.Title = "MightyFrOg's Stratagem Reciver";
Console.CancelKeyPress += (_, eventArgs) =>
{
    eventArgs.Cancel = true;
    shutdown.Cancel();
};

Console.WriteLine("MIGHTYFROG'S STRATAGEM RECIVER");
Console.WriteLine("Searching this Wi-Fi for the phone app; no PC IP or firewall setup is needed.");
Console.WriteLine("Keep the phone app open and approve the connection request there.");
Console.WriteLine("Keyboard input is sent only to the active Windows game. Press Ctrl+C to exit.");

while (!shutdown.IsCancellationRequested)
{
    TcpClient? client = null;
    try
    {
        client = await DiscoverAndConnect(phonePort, discoveryPort, shutdown.Token);
        Console.WriteLine("Phone approved. Synchronizing keyboard mapping...");
        await HandleClient(client, shutdown.Token);
    }
    catch (OperationCanceledException) when (shutdown.IsCancellationRequested)
    {
        break;
    }
    catch (SocketException error)
    {
        Console.WriteLine($"Network connection ended: {error.Message}");
    }
    catch (IOException error)
    {
        Console.WriteLine($"Connection ended: {error.Message}");
    }
    catch (JsonException error)
    {
        Console.WriteLine($"Invalid phone message: {error.Message}");
    }
    finally
    {
        Keyboard.ReleaseAll();
        client?.Dispose();
    }
}

static async Task<TcpClient> DiscoverAndConnect(int phonePort, int discoveryPort, CancellationToken cancellationToken)
{
    var discoveryMessage = JsonSerializer.SerializeToUtf8Bytes(new { type = "discover", version = 1 });
    Console.WriteLine("Waiting for the phone app...");
    while (!cancellationToken.IsCancellationRequested)
    {
        using var discovery = new UdpClient(0) { EnableBroadcast = true };
        foreach (var address in GetBroadcastAddresses())
        {
            try
            {
                await discovery.SendAsync(discoveryMessage, new IPEndPoint(address, discoveryPort));
            }
            catch (SocketException)
            {
                // Continue attempting discovery on the other active network adapters.
            }
        }

        using var receiveTimeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        receiveTimeout.CancelAfter(TimeSpan.FromSeconds(2));
        UdpReceiveResult offer;
        try
        {
            offer = await discovery.ReceiveAsync(receiveTimeout.Token);
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            continue;
        }

        using var offerDocument = JsonDocument.Parse(offer.Buffer);
        var offerRoot = offerDocument.RootElement;
        if (!offerRoot.TryGetProperty("type", out var type) ||
            type.ValueKind != JsonValueKind.String ||
            type.GetString() != "offer" ||
            !offerRoot.TryGetProperty("port", out var portValue) ||
            !portValue.TryGetInt32(out var targetPort) ||
            targetPort is < 1 or > 65535)
        {
            continue;
        }

        var client = new TcpClient { NoDelay = true };
        try
        {
            using var connectTimeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            connectTimeout.CancelAfter(TimeSpan.FromSeconds(5));
            await client.ConnectAsync(offer.RemoteEndPoint.Address, targetPort, connectTimeout.Token);

            var stream = client.GetStream();
            using var reader = new StreamReader(stream, Encoding.UTF8, leaveOpen: true);
            await using var writer = new StreamWriter(stream, new UTF8Encoding(false), leaveOpen: true) { AutoFlush = true };
            await writer.WriteLineAsync(JsonSerializer.Serialize(new { type = "hello", version = 1 }));
            Console.WriteLine($"Phone found at {offer.RemoteEndPoint.Address}; approve the request on the phone.");

            using var approvalTimeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            approvalTimeout.CancelAfter(TimeSpan.FromSeconds(65));
            var responseLine = await reader.ReadLineAsync(approvalTimeout.Token);
            if (responseLine is null)
            {
                throw new IOException("Phone closed before approving the receiver.");
            }

            using var response = JsonDocument.Parse(responseLine);
            if (!response.RootElement.TryGetProperty("ok", out var ok) || !ok.GetBoolean())
            {
                var error = response.RootElement.TryGetProperty("error", out var detail)
                    ? detail.GetString()
                    : null;
                Console.WriteLine(error ?? "Phone did not approve the receiver.");
                client.Dispose();
                continue;
            }

            return client;
        }
        catch (SocketException error)
        {
            client.Dispose();
            Console.WriteLine($"Could not connect to the phone: {error.Message}");
        }
        catch (IOException error)
        {
            client.Dispose();
            Console.WriteLine($"Phone link failed: {error.Message}");
        }
        catch (JsonException error)
        {
            client.Dispose();
            Console.WriteLine($"Phone sent an invalid link response: {error.Message}");
        }
        catch (InvalidOperationException error)
        {
            client.Dispose();
            Console.WriteLine($"Phone link response was invalid: {error.Message}");
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            client.Dispose();
            Console.WriteLine("Phone did not finish approving the receiver.");
        }
    }

    throw new OperationCanceledException(cancellationToken);
}

static int GetPort(string[] arguments, string option, int defaultPort)
{
    for (var index = 0; index < arguments.Length; index++)
    {
        if (arguments[index] != option)
        {
            continue;
        }
        if (index + 1 >= arguments.Length ||
            !int.TryParse(arguments[index + 1], out var port) ||
            port is < 1 or > 65535)
        {
            throw new ArgumentException($"A valid port must follow {option}.");
        }
        return port;
    }
    return defaultPort;
}

static IEnumerable<IPAddress> GetBroadcastAddresses()
{
    var addresses = new HashSet<IPAddress> { IPAddress.Broadcast };
    foreach (var network in NetworkInterface.GetAllNetworkInterfaces()
                 .Where(item => item.OperationalStatus == OperationalStatus.Up))
    {
        foreach (var unicast in network.GetIPProperties().UnicastAddresses)
        {
            if (unicast.Address.AddressFamily != AddressFamily.InterNetwork ||
                IPAddress.IsLoopback(unicast.Address) ||
                unicast.IPv4Mask is null)
            {
                continue;
            }

            var ipBytes = unicast.Address.GetAddressBytes();
            var maskBytes = unicast.IPv4Mask.GetAddressBytes();
            var broadcastBytes = new byte[4];
            for (var index = 0; index < broadcastBytes.Length; index++)
            {
                broadcastBytes[index] = (byte)(ipBytes[index] | ~maskBytes[index]);
            }
            addresses.Add(new IPAddress(broadcastBytes));
        }
    }
    return addresses;
}

static async Task HandleClient(TcpClient client, CancellationToken cancellationToken)
{
    client.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.KeepAlive, true);
    await using var stream = client.GetStream();
    using var reader = new StreamReader(stream, Encoding.UTF8, leaveOpen: true);
    await using var writer = new StreamWriter(stream, new UTF8Encoding(false), leaveOpen: true) { AutoFlush = true };

    while (!cancellationToken.IsCancellationRequested)
    {
        var line = await reader.ReadLineAsync(cancellationToken);
        if (line is null)
        {
            return;
        }

        try
        {
            using var message = JsonDocument.Parse(line);
            await HandleMessage(message.RootElement, writer);
        }
        catch (Exception error) when (error is JsonException or ArgumentException or InvalidOperationException or Win32Exception)
        {
            await Reply(writer, false, error.Message);
        }
    }
}

static async Task HandleMessage(JsonElement message, StreamWriter writer)
{
    switch (GetString(message, "type"))
    {
        case "configure":
            Keyboard.Configure(message.GetProperty("mapping"));
            Console.WriteLine("Receiver ready; keyboard mapping accepted.");
            await Reply(writer, true);
            break;
        case "ping":
            await Reply(writer, true);
            break;
        case "activate":
            Keyboard.Press(Keyboard.Mapping.Activate);
            await Reply(writer, true);
            break;
        case "direction":
            var direction = GetString(message, "direction");
            var key = direction switch
            {
                "UP" => Keyboard.Mapping.Up,
                "DOWN" => Keyboard.Mapping.Down,
                "LEFT" => Keyboard.Mapping.Left,
                "RIGHT" => Keyboard.Mapping.Right,
                _ => throw new ArgumentException("Direction must be UP, DOWN, LEFT, or RIGHT.")
            };
            if (!message.GetProperty("pressed").GetBoolean())
            {
                Keyboard.Release(key);
            }
            else
            {
                Keyboard.Press(key);
            }
            await Reply(writer, true);
            break;
        case "releaseAll":
            Keyboard.ReleaseAll();
            await Reply(writer, true);
            break;
        default:
            throw new ArgumentException("Unknown command.");
    }
}

static string GetString(JsonElement element, string property) =>
    element.TryGetProperty(property, out var value) && value.ValueKind == JsonValueKind.String
        ? value.GetString() ?? string.Empty
        : string.Empty;

static Task Reply(StreamWriter writer, bool ok, string error = "") =>
    writer.WriteLineAsync(JsonSerializer.Serialize(new { ok, error }));

static class Keyboard
{
    private const uint InputKeyboard = 1;
    private const uint KeyExtended = 0x0001;
    private const uint KeyUp = 0x0002;
    private static readonly HashSet<ushort> HeldKeys = [];
    public static KeyMap Mapping { get; private set; } = KeyMap.Default;

    public static void Configure(JsonElement value)
    {
        var mapping = new KeyMap(
            VirtualKey.Parse(ReadString(value, "activate")),
            VirtualKey.Parse(ReadString(value, "up")),
            VirtualKey.Parse(ReadString(value, "down")),
            VirtualKey.Parse(ReadString(value, "left")),
            VirtualKey.Parse(ReadString(value, "right")));
        if (new[] { mapping.Activate, mapping.Up, mapping.Down, mapping.Left, mapping.Right }.Distinct().Count() != 5)
        {
            throw new ArgumentException("The activation and four direction keys must be different.");
        }
        ReleaseAll();
        Mapping = mapping;
    }

    public static void Press(ushort key)
    {
        if (HeldKeys.Contains(key)) return;
        SendKey(key, keyUp: false);
        HeldKeys.Add(key);
    }

    public static void Release(ushort key)
    {
        if (!HeldKeys.Contains(key)) return;
        SendKey(key, keyUp: true);
        HeldKeys.Remove(key);
    }

    public static void ReleaseAll()
    {
        foreach (var key in HeldKeys.ToArray())
        {
            SendKey(key, keyUp: true);
            HeldKeys.Remove(key);
        }
    }

    private static void SendKey(ushort key, bool keyUp)
    {
        var flags = keyUp ? KeyUp : 0;
        if (key is 0x25 or 0x26 or 0x27 or 0x28)
        {
            flags |= KeyExtended;
        }

        var input = new Input
        {
            Type = InputKeyboard,
            Union = new InputUnion
            {
                Keyboard = new KeyboardInput
                {
                    VirtualKey = key,
                    Flags = flags
                }
            }
        };
        if (SendInput(1, [input], Marshal.SizeOf<Input>()) != 1)
        {
            throw new Win32Exception(Marshal.GetLastWin32Error(), "Windows did not accept the stratagem key input.");
        }
    }

    private static string ReadString(JsonElement element, string property) =>
        element.TryGetProperty(property, out var value) && value.ValueKind == JsonValueKind.String
            ? value.GetString() ?? string.Empty
            : string.Empty;

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint numberOfInputs, Input[] inputs, int sizeOfInput);

    [StructLayout(LayoutKind.Sequential)]
    private struct Input
    {
        public uint Type;
        public InputUnion Union;
    }

    [StructLayout(LayoutKind.Explicit)]
    private struct InputUnion
    {
        [FieldOffset(0)]
        public KeyboardInput Keyboard;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct KeyboardInput
    {
        public ushort VirtualKey;
        public ushort ScanCode;
        public uint Flags;
        public uint Time;
        public UIntPtr ExtraInfo;
    }
}

sealed record KeyMap(ushort Activate, ushort Up, ushort Down, ushort Left, ushort Right)
{
    public static KeyMap Default => new(
        VirtualKey.Parse("LEFTCTRL"),
        VirtualKey.Parse("UP"),
        VirtualKey.Parse("DOWN"),
        VirtualKey.Parse("LEFT"),
        VirtualKey.Parse("RIGHT"));
}

static class VirtualKey
{
    public static ushort Parse(string value)
    {
        var key = value.Trim().ToUpperInvariant();
        if (key.Length == 1 && ((key[0] >= 'A' && key[0] <= 'Z') || (key[0] >= '0' && key[0] <= '9')))
        {
            return key[0];
        }
        if (key.Length is 2 or 3 && key[0] == 'F' && ushort.TryParse(key[1..], out var functionKey) && functionKey is >= 1 and <= 12)
        {
            return (ushort)(0x70 + functionKey - 1);
        }
        return key switch
        {
            "CTRL" or "LEFTCTRL" => 0xA2,
            "RIGHTCTRL" => 0xA3,
            "SHIFT" or "LEFTSHIFT" => 0xA0,
            "RIGHTSHIFT" => 0xA1,
            "ALT" or "LEFTALT" => 0xA4,
            "RIGHTALT" => 0xA5,
            "SPACE" => 0x20,
            "UP" => 0x26,
            "DOWN" => 0x28,
            "LEFT" => 0x25,
            "RIGHT" => 0x27,
            _ => throw new ArgumentException($"Unsupported key '{value}'.")
        };
    }
}
