$ErrorActionPreference = "Stop"

$receiver = Join-Path $PSScriptRoot "..\windows-receiver\publish\MightyFrOg's Stratagem Reciver.exe"
if (-not (Test-Path -LiteralPath $receiver)) {
    throw "Publish the receiver first with dotnet publish."
}

$phonePort = 38583
$discoveryPort = 38584
$logPath = Join-Path $env:TEMP "stratagem-caller-receiver-test.log"
$udp = [System.Net.Sockets.UdpClient]::new($discoveryPort)
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Any, $phonePort)
$process = $null
$client = $null

try {
    $udp.Client.ReceiveTimeout = 15000
    $listener.Start()
    $process = Start-Process -FilePath $receiver -ArgumentList @("--phone-port", $phonePort, "--discovery-port", $discoveryPort) -PassThru -NoNewWindow -RedirectStandardOutput $logPath

    $endpoint = [System.Net.IPEndPoint]::new([System.Net.IPAddress]::Any, 0)
    $discoveryBytes = $udp.Receive([ref]$endpoint)
    $discovery = [System.Text.Encoding]::UTF8.GetString($discoveryBytes) | ConvertFrom-Json
    if ($discovery.type -ne "discover" -or $discovery.version -ne 1) {
        throw "Receiver discovery packet was invalid."
    }

    $offer = [System.Text.Encoding]::UTF8.GetBytes("{`"type`":`"offer`",`"version`":1,`"port`":$phonePort}")
    [void]$udp.Send($offer, $offer.Length, $endpoint)
    $acceptTask = $listener.AcceptTcpClientAsync()
    $acceptedTask = [System.Threading.Tasks.Task]::WhenAny(
        $acceptTask,
        [System.Threading.Tasks.Task]::Delay([TimeSpan]::FromSeconds(15))
    ).GetAwaiter().GetResult()
    if (-not [object]::ReferenceEquals($acceptedTask, $acceptTask)) {
        throw "Receiver did not connect to the phone simulator."
    }
    $client = $acceptTask.GetAwaiter().GetResult()
    $client.NoDelay = $true
    $stream = $client.GetStream()
    $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8, $false, 1024, $true)
    $writer = [System.IO.StreamWriter]::new($stream, [System.Text.UTF8Encoding]::new($false), 1024, $true)
    $writer.AutoFlush = $true

    $hello = $reader.ReadLine() | ConvertFrom-Json
    if ($hello.type -ne "hello" -or $hello.version -ne 1) {
        throw "Receiver hello was invalid."
    }
    $writer.WriteLine('{"ok":true}')

    $mapping = @{ activate = "LEFTCTRL"; up = "UP"; down = "DOWN"; left = "LEFT"; right = "RIGHT" }
    foreach ($message in @(
        @{ type = "configure"; mapping = $mapping },
        @{ type = "ping" },
        @{ type = "releaseAll" }
    )) {
        $writer.WriteLine(($message | ConvertTo-Json -Compress))
        $response = $reader.ReadLine() | ConvertFrom-Json
        if (-not $response.ok) {
            throw "Receiver rejected $($message.type): $($response.error)"
        }
    }
    if (-not (Select-String -LiteralPath $logPath -Pattern "Receiver ready; keyboard mapping accepted." -Quiet)) {
        throw "Receiver did not report readiness after accepting its mapping."
    }
    Write-Output "Receiver discovery, approval, mapping, ping, and release-all protocol passed."
}
finally {
    if ($client) { $client.Dispose() }
    $listener.Stop()
    $udp.Dispose()
    if ($process -and -not $process.HasExited) { Stop-Process -Id $process.Id }
    Remove-Item -LiteralPath $logPath -Force -ErrorAction SilentlyContinue
}
