arg = getArgument();
args = split(arg,'#');
print("arguments:");
print(args[0]);
print(args[1]);
run(args[0]+" ", args[1]);
print("Done.");
eval("script", "System.exit(0);");
