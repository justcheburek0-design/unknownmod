# Standalone test client retired at the user's request. Old instructions must
# not silently recreate its profile; the installed Horizon launcher is used now.
param(
    [string]$Name = 'JustCheburek',
    [string]$Server = '127.0.0.1:25567',
    [switch]$VoiceOnly
)
throw 'The standalone test client has been retired. Use the Horizon launcher you installed. The old profile and launch script are archived in build/horizon/retired-clients. The shared engine is retained for the server.'
