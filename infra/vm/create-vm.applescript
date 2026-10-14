on run argv
	set vmName to item 1 of argv
	set cpuCount to (item 2 of argv) as integer
	set memMb to (item 3 of argv) as integer
	set dataMb to (item 4 of argv) as integer
	set rootImg to POSIX file (item 5 of argv)
	set seedIso to POSIX file (item 6 of argv)
	tell application "UTM"
		set vm to make new virtual machine with properties {backend:qemu, configuration:{name:vmName, architecture:"aarch64", memory:memMb, cpu cores:cpuCount, drives:{{source:rootImg}, {guest size:dataMb}, {interface:VirtIO, source:seedIso}}, network interfaces:{{mode:shared}}}}
		return id of vm
	end tell
end run
