// this script is used to generate Extensions/extFluidBufProc.sc
(
var getSignature = { |m|
	var args = m.argNames.as(Array);
	var vals = m.prototypeFrame.as(Array);
	var removeArgs = [\this, \source, \server, \action] ++
	[\features, \indices, \destination].detect(args.includes(_));
	removeArgs.collect(args.indexOf(_)).sort.reverse.do { |n|
			[vals, args].do(_.removeAt(n))
	};

	args.collect { |argName, n|
		var val = vals[n];
		if (val.isNil) { argName } {
			argName ++ " = " ++ if (val.isArray) {"#"++val } { val }
		}
	}.join(", ")
};

var ext = FluidBufProcessor.subclasses
.select { |sc|
	var m = sc.class.findMethod(\process);
	m.notNil and: {
		[\source, \server, \action].every(m.argNames.includes(_))
		and: {
			[\stats, \features, \indices, \destination].any(m.argNames.includes(_))
		}
	}
}
.collect { |sc|
	var name = sc.name;
	var method = sc.class.findMethod(\process);
	var signature = getSignature.value(method);
	var args = method.argNames.drop(1).as(Array)
	.replace(\server, '~src.server')
	.replace(\source, '~src')
	.replace(\action, '~done');
	case
	{ args.includes(\stats) } { args = args.replace(\stats, '~dst') }
	{ args.includes(\features) } { args = args.replace(\features, '~dst') }
	{ args.includes(\indices) } { args = args.replace(\indices, '~dst') }
	{ args.includes(\destination) } { args = args.replace(\destination, '~dst') };
"+ % {
\t*chain { |%|
\t\t^this.process(%);
\t}
}".format(name, signature, args.join(", "));
}
.join($\n);
File.use("Classes/Extensions/extFluidBufProc.sc", "w") { |f| f.write(ext) };
0.exit;
)
