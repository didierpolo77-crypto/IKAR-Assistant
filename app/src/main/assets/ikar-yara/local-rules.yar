// IKAR starter rules. Supported subset: quoted ASCII strings + any/all/N of them.
// Heuristic indicators only; not a replacement for official libyara.
rule IKAR_EICAR_Test_File {
 strings:
  $eicar = "EICAR-STANDARD-ANTIVIRUS-TEST-FILE"
 condition:
  any of them
}
rule IKAR_Suspicious_PowerShell_Download {
 strings:
  $a = "DownloadString("
  $b = "FromBase64String("
  $c = "powershell -enc"
 condition:
  2 of them
}
rule IKAR_Suspicious_Script_Dropper {
 strings:
  $a = "WScript.Shell"
  $b = "ADODB.Stream"
  $c = "CreateObject("
 condition:
  2 of them
}
