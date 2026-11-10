$ErrorActionPreference = 'Stop'
$root = 'C:\openlist\files'
$origin = 'http://42.193.123.248:5244'
$listener = New-Object System.Net.HttpListener
$listener.Prefixes.Add('http://+:80/')
$listener.Start()
Write-Output 'XecWeb listening on :80 (v2: www site + upload page + short links)'
$types = @{
    '.html' = 'text/html; charset=utf-8'
    '.css'  = 'text/css; charset=utf-8'
    '.js'   = 'application/javascript; charset=utf-8'
    '.json' = 'application/json; charset=utf-8'
    '.png'  = 'image/png'
    '.jpg'  = 'image/jpeg'
    '.jpeg' = 'image/jpeg'
    '.webp' = 'image/webp'
    '.svg'  = 'image/svg+xml'
    '.ico'  = 'image/x-icon'
    '.txt'  = 'text/plain; charset=utf-8'
}
function Send-File($ctx, $path) {
    $ext = [IO.Path]::GetExtension($path).ToLowerInvariant()
    $lm = (Get-Item $path).LastWriteTimeUtc.ToString('R')
    $ims = $ctx.Request.Headers['If-Modified-Since']
    if ($ims -and $ims -eq $lm) {
        $ctx.Response.StatusCode = 304
        $ctx.Response.Headers['Last-Modified'] = $lm
        return
    }
    $bytes = [IO.File]::ReadAllBytes($path)
    if ($types.ContainsKey($ext)) { $ctx.Response.ContentType = $types[$ext] }
    else { $ctx.Response.ContentType = 'application/octet-stream' }
    $ctx.Response.Headers['Cache-Control'] = 'no-cache'
    $ctx.Response.Headers['Last-Modified'] = $lm
    $ctx.Response.ContentLength64 = $bytes.Length
    $ctx.Response.OutputStream.Write($bytes, 0, $bytes.Length)
}
while ($true) {
    $ctx = $listener.GetContext()
    try {
        $path = $ctx.Request.Url.AbsolutePath
        if ($path -eq '/' -or $path -eq '/index.html') {
            Send-File $ctx (Join-Path $root 'www\index.html')
        } elseif ($path -eq '/upload.html') {
            Send-File $ctx (Join-Path $root 'upload.html')
        } elseif ($path.StartsWith('/assets/')) {
            $rel = $path.Substring('/assets/'.Length)
            $base = [IO.Path]::GetFullPath((Join-Path $root 'www\assets'))
            $full = [IO.Path]::GetFullPath((Join-Path $base $rel))
            if ($full.StartsWith($base, [StringComparison]::OrdinalIgnoreCase) -and [IO.File]::Exists($full)) {
                Send-File $ctx $full
            } else { $ctx.Response.StatusCode = 404 }
        } elseif ($path.StartsWith('/uploads/') -or $path.StartsWith('/d/uploads/')) {
            $ctx.Response.RedirectLocation = $origin + $path
            $ctx.Response.StatusCode = 302
        } elseif ($path.StartsWith('/gki/') -or $path.StartsWith('/kpm/') -or $path.StartsWith('/apk/') -or $path.StartsWith('/debug/')) {
            $ctx.Response.RedirectLocation = $origin + '/d' + $path
            $ctx.Response.StatusCode = 302
        } else {
            $ctx.Response.StatusCode = 404
            $msg = [Text.Encoding]::UTF8.GetBytes('not found')
            $ctx.Response.OutputStream.Write($msg, 0, $msg.Length)
        }
    } catch {
        try { $ctx.Response.StatusCode = 500 } catch {}
    }
    finally {
        try { $ctx.Response.OutputStream.Close() } catch {}
    }
}
