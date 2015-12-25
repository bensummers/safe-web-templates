package org.haplo.template.html;

import java.util.Stack;
import java.util.HashSet;

final public class Parser {
    private CharSequence source;
    private String templateName;
    private int pos = 0;
    private Context context = Context.TEXT;
    private Stack<Node> nesting;
    private String childlessTagName = null;
    private boolean inEnclosingViewBlock = false;
    private int nextRememberIndex = 0;

    public Parser(CharSequence source, String templateName) {
        this.source = source;
        this.templateName = templateName;
        this.nesting = new Stack<Node>();
    }

    public Template parse() throws ParseException {
        Template template = new Template(this.templateName, parseList(-1, "template"), this.nextRememberIndex);
        if(!this.nesting.empty()) { throw new RuntimeException("logic error"); }
        return template;
    }

    protected Context getCurrentParseContext() {
        return this.context;
    }

    protected NodeList parseList(int endOfListCharacter, String what) throws ParseException {
        int listStart = this.pos;
        NodeList nodes = new NodeList();
        this.nesting.push(nodes);
        boolean seenEndOfList = false;
        Node node;
        while((node = parseOneValue(endOfListCharacter)) != null) {
            if(node == END_OF_LIST) {
                seenEndOfList = true;
                break;
            }
            nodes.add(node, this.context);
        }
        if((endOfListCharacter != -1) && !seenEndOfList) {
            error("Did not find end of "+what, listStart);
        }
        popNestingAndCheckNodeWas(nodes, listStart);
        return nodes;
    }

    protected Node parseOneValue(int endOfListCharacter) throws ParseException {
        CharSequence s = symbol();
        if(s == null) { return null; }
        char firstChar = s.charAt(0);
        int singleChar = (s.length() == 1) ? firstChar : -999;
        if(singleChar == '"') {
            String qstr = quotedString();
            if(this.context == Context.URL) {
                // Needs special escaping for URL context
                qstr = Escape.escapeString(qstr, Context.URL_PATH);
            }
            return new NodeLiteral(qstr);
        } else if(singleChar == '<') {
            return parseTag().orSimplifiedNode();
        } else if(singleChar == '[') {
            if(this.context == Context.URL) {
                return parseURL(']');
            } else {
                if(this.context == Context.TEXT) {
                    error("Lists are not allowed within document text");
                }
                return parseList(']', "list").orSimplifiedNode();
            }
        } else if(singleChar == endOfListCharacter) {
            return END_OF_LIST;
        } else if((singleChar == ']') || (singleChar == '}') ||
                  (singleChar == '(') || (singleChar == ')') || // both () directions
                  (singleChar == '>') ||
                  (singleChar == '?') || (singleChar == '!') || (singleChar == '*') // URL syntax
                ) {
            error("Unexpected "+s);
        }
        // Special case enclosing view block
        if(firstChar == '^') {
            return parseEnclosingViewBlock(s);
        }
        // Special case for '.'
        if(firstChar == '.') {
            if(s.length() == 1) {
                return new NodeValueThis();
            } else {
                error("Value names cannot be prefixed with . (single dot is used for 'this')");
            }
        }
        // Is it a value, or a function? Get next symbol to check.
        int savedPos = this.pos;
        CharSequence nextSymbol = symbol();
        if(symbolIsSingleChar(nextSymbol, '(')) {
            return parseFunction(s.toString());
        } else {
            // Simple value, restore position then return
            this.pos = savedPos;
            return new NodeValue(s.toString());
        }
    }

    private static final Node END_OF_LIST = new Node() {
        public void render(StringBuilder builder, Driver driver, Object view, Context context) throws RenderException {
            throw new RenderException(driver, "Logic error in template engine, END_OF_LIST should never be rendered");
        }
    };

    protected <T extends Node> T parseOneValueOfType(Class<T> type, String expected, int endOfListCharacter, int startPos) throws ParseException {
        Node node = parseOneValue(endOfListCharacter);
        if((node == null) || (node == END_OF_LIST) || !(type.isInstance(node))) {
            error("Expected "+expected, startPos);
        }
        return type.cast(node);
    }

    protected Node parseEnclosingViewBlock(CharSequence name) throws ParseException {
        if(this.inEnclosingViewBlock) {
            error("Enclosing view blocks may not contain other enclosing view blocks");
        }
        this.inEnclosingViewBlock = true;
        for(int n = 0; n < name.length(); ++n) {
            if(name.charAt(n) != '^') {
                error("Enclosing view block names may not contain other characters");
            }
        }
        if(!symbolIsSingleChar(symbol(), '{')) {
            error("Enclosing view block names must be followed by a block");
        }
        NodeFunction.ChangesView nodeWhichChangesView = null;
        int depth = name.length();
        for(int n = this.nesting.size() - 1; n >= 0 && depth > 0; --n) {
            Node s = this.nesting.get(n);
            if(s instanceof NodeFunction.ChangesView) {
                depth--;
                if(depth == 0) {
                    nodeWhichChangesView = (NodeFunction.ChangesView)s;
                    break;
                }
            }
        }
        if(nodeWhichChangesView == null) {
            error("There are not "+name.length()+" enclosing views");
        }
        // Ask the function node to remember the value when the view is remembered
        nodeWhichChangesView.shouldRemember(this);
        Node block = parseList('}', "enclosing view block").getListHeadMaybe();
        if(block == null) {
            error("Enclosing view blocks cannot be empty");
        }
        this.inEnclosingViewBlock = false;
        return new NodeEnclosingView(nodeWhichChangesView.getRememberedViewIndex(), block);
    }

    // Called after the first ( has been returned from symbol()
    protected Node parseFunction(String functionName) throws ParseException {
        // Special cases for pseudo functions
        if(functionName.equals("url")) {
            return parseURL(')');
        } else if(functionName.equals("scriptTag")) {
            return new NodeScriptTag(parseURL(')'));
        }
        int functionStartPos = this.pos;
        Context oldContext = this.context;
        this.context = Context.FUNCTION_ARGUMENTS;
        NodeList arguments = parseList(')', "arguments");
        this.context = oldContext;
        NodeFunction fn = functionNodeFromName(functionName);
        fn.setArguments(this, arguments.getListHeadMaybe());
        this.nesting.push(fn);
        // Are there any blocks?
        CharSequence possibleBlockName = Node.BLOCK_ANONYMOUS;
        while(true) {
            int savedPos = this.pos, blockNameEndPos = this.pos;
            if(possibleBlockName == null) {
                // The first block doesn't have a name, but further blocks do.
                possibleBlockName = symbol();
                blockNameEndPos = this.pos;
                // The name may be a quoted string.
                if(symbolIsSingleChar(possibleBlockName, '"')) {
                    possibleBlockName = quotedString();
                }
            }
            CharSequence possibleStartBlock = symbol();
            if(symbolIsSingleChar(possibleStartBlock, '{')) {
                String blockName = possibleBlockName.toString();
                // Work out a readable block name for the error message
                String listBlockName = (possibleBlockName == Node.BLOCK_ANONYMOUS) ?
                    "block" : blockName+" block";
                Node block = parseList('}', listBlockName).orSimplifiedNode();
                fn.addBlock(this, blockName, block, blockNameEndPos);
            } else {
                this.pos = savedPos;
                break;
            }
            possibleBlockName = null;
        }
        fn.postParse(this, functionStartPos);
        popNestingAndCheckNodeWas(fn, functionStartPos);
        return fn;
    }

    protected NodeFunction functionNodeFromName(String functionName) throws ParseException {
        switch(functionName) {
            case "within":      return new NodeFunctionWithin();
            case "if":          return new NodeFunctionConditional(false);
            case "unless":      return new NodeFunctionConditional(true);
            case "each":        return new NodeFunctionEach();
            case "switch":      return new NodeFunctionSwitch();
            case "do"    :      return new NodeFunctionDo();
            case "render":      return new NodeFunctionRender();
            case "unsafeHTML":  return new NodeFunctionUnsafeHTML();
            case "yield":       return new NodeFunctionYield(Node.BLOCK_ANONYMOUS);
            default: break;
        }
        if(functionName.startsWith("template")) {
            if((functionName.length() <= 9) || (functionName.charAt(8) != ':')) {
                error("Bad included template function name, must start 'template:'");
            }
            return new NodeFunctionTemplate(functionName.substring(9));
        } else if(functionName.startsWith("yield")) {
            if((functionName.length() <= 6) || (functionName.charAt(5) != ':')) {
                error("Bad named yield function name, must start 'yield:'");
            }
            return new NodeFunctionYield(functionName.substring(6));
        }
        return new NodeFunctionGeneric(functionName);
    }

    protected Node parseTag() throws ParseException {
        if(this.context != Context.TEXT) {
            error("Tags are not valid in this context");
        }
        int tagStartPos = this.pos;
        CharSequence name = symbol();
        if(symbolIsSingleChar(name, '/')) {
            Node closeTag = parseCloseTag(tagStartPos);
            this.childlessTagName = null;
            return closeTag;
        }
        this.context = Context.TAG;
        checkTagName(name, false, tagStartPos);
        String tagName = name.toString();
        if(tagName.equals("script")) {
            error("<script> tags are not allowed. Use scriptTag(...) to generate tags which include external scripts.");
        } else if(tagName.equals("style")) {
            // Style tags aren't allowed because it needs a special parsing mode and escaping, and nothing
            // is gained by allowing inline stylesheets.
            error("<style> tags are not allowed. Use the <link> tag to include external stylesheets.");
        }
        if(this.childlessTagName != null) {
            error("Cannot include other tags inside <"+this.childlessTagName+">");
        } else if(HTML.cannotContainChildNodes(tagName)) {
            this.childlessTagName = tagName;
        }
        NodeTag tag = new NodeTag(tagName);
        HashSet<String> seenAttributes = new HashSet<String>(4);
        String attributeName = null;
        while(true) {
            CharSequence s = symbol();
            if(s == null) { error("Unexpected end of template in tag"); }
            if(symbolIsSingleChar(s, '>')) {
                break;
            } else if(symbolIsSingleChar(s, '=')) {
                if(attributeName == null) {
                    error("Unexpected = in tag");
                }
                if(!seenAttributes.add(attributeName)) {
                    error("Duplicate '"+attributeName+"' attribute in <"+tagName+">");
                }
                // Automatically move to URL escaping & parsing mode if attribute is known to contains URLs
                this.context = HTML.attributeIsURL(tagName, attributeName) ?
                        Context.URL :
                        Context.ATTRIBUTE_VALUE;
                tag.addAttribute(
                        attributeName,
                        checkedTagAttribute(attributeName, parseOneValue(-1)),
                        this.context);
                this.context = Context.TAG;
                attributeName = null;
            } else if(symbolIsSingleChar(s, '/')) {
                error("Self closing tags are not allowed", tagStartPos);
            } else {
                if(attributeName != null) {
                    error("Expected = after attribute name");
                }
                if(!(validAttributeName(s))) {
                    error("Invalid attribute name: '"+s+"' (attribute names must be lower case, "+
                        "and not begin with 'on' as these attributes are security risks)");
                }
                attributeName = s.toString();
            }
        }
        if(attributeName != null) {
            error("No attribute value in tag");
        }
        if(!HTML.isVoidTag(tagName)) {
            // Void tags are not closed, so aren't part of the nested structure
            this.nesting.push(tag);
        }
        this.context = Context.TEXT;
        return tag;
    }

    protected Node checkedTagAttribute(String attributeName, Node value) throws ParseException {
        switch(attributeName) {
            case "style":
            case "id":
            case "class":
                if(!value.whitelistForLiteralStringOnly()) {
                    error("style".equals(attributeName) ?
                        "style attributes must always be a literal string or conditionals choosing between literal strings "+
                            "(CSS escaping not supported)" :
                        "id and class attributes must always be a literal string or conditionals choosing between literal strings. "+
                            "Use if()/switch() to determine or whitelist values (using untrusted id/class attributes is likely "+
                            "to introduce client side security bugs)");
                }
                break;
            default:
                break;
        }
        return value;
    }

    protected Node parseCloseTag(int tagStartPos) throws ParseException {
        CharSequence name = symbol();
        checkTagName(name, true, tagStartPos);
        CharSequence terminator = symbol();
        if(symbolIsSingleChar(terminator, '/')) {
            error("Self closing tags are not allowed", tagStartPos);
        } else if(!symbolIsSingleChar(terminator, '>')) {
            error("A closing tag may not have attributes");
        }
        String tagName = name.toString();
        if(HTML.isVoidTag(tagName)) {
            error("Void tags may not have close tags", tagStartPos);
        }
        Node openingTag = this.nesting.pop();
        if(!((openingTag instanceof NodeTag) && ((NodeTag)openingTag).getName().equals(tagName))) {
            error("Unexpected tag </"+name+">, tags must be balanced", tagStartPos);
        }
        return new NodeLiteral("</"+name+">");
    }

    protected void checkTagName(CharSequence name, boolean isCloseTag, int tagStartPos) throws ParseException {
        if(name == null) { error("Unexpected end of template after <"); }
        int len = name.length();
        for(int i = 0; i < len; ++i) {
            char c = name.charAt(i);
            if(!( ((c >= 'a') && (c <= 'z')) || ((c >= '0') && (c <= '9')) )) {
                error("Invalid tag name <"+(isCloseTag?"/":"")+name+"> (must be lower case, a-z0-9 only)", tagStartPos);
            }
        }
    }

    private boolean validRestrictedName(CharSequence name) {
        int len = name.length();
        for(int i = 0; i < len; ++i) {
            char c = name.charAt(i);
            if(!( ((c >= 'a') && (c <= 'z')) || ((c >= '0') && (c <= '9')) || (c == '-') || (c == '_') )) {
                return false;
            }
        }
        return true;
    }

    private boolean validAttributeName(CharSequence name) {
        if(!validRestrictedName(name)) { return false; }
        return !((name.length() >= 2) && (name.charAt(0) == 'o') && (name.charAt(1) == 'n'));
    }

    protected NodeURL parseURL(char endOfListCharacter) throws ParseException {
        int urlStart = this.pos;
        Context oldContext = this.context;
        this.context = Context.URL;
        NodeURL url = new NodeURL();
        this.nesting.push(url);
        boolean inParameters = false;
        boolean inFragment = false;
        while(true) {
            int symbolStart = this.pos;
            CharSequence s = symbol();
            if(s == null) {
                error("Did not find end of URL", urlStart);
            }
            int singleChar = (s.length() == 1) ? s.charAt(0) : -999;
            if(singleChar == endOfListCharacter) {
                break;
            } else if(inParameters) {
                if(singleChar == '*') {
                    NodeValue value = parseOneValueOfType(NodeValue.class, "dictionary value after *", endOfListCharacter, this.pos);
                    url.addParameterInstructionAllFromDictionary(value);
                } else if(singleChar == '!') {
                    CharSequence name = symbol();
                    if(name == null) { error("Expected key name"); }
                    url.addParameterInstructionRemoveKey(name.toString());
                } else if(singleChar == '#') {
                    inParameters = false;
                    inFragment = true;
                } else {
                    if(!(validRestrictedName(s))) {
                        error("Invalid literal URL parameter name: '"+s+"'");
                    }
                    if(!symbolIsSingleChar(symbol(), '=')) {
                        error("After ?, URLs must be formed of key=value, !key or *dictionary");
                    }
                    this.context = Context.UNSAFE;  // escaping happens in NodeURL's render()
                    Node value = parseOneValueOfType(Node.class, "URL parameter value after =", endOfListCharacter, this.pos);
                    this.context = Context.URL;
                    url.addParameterInstructionAddKeyValue(s.toString(), value);
                }
            } else {
                if(singleChar == '?') {
                    inParameters = true;
                } else if(singleChar == '#') {
                    inFragment = true;
                } else if((singleChar == '=') || (singleChar == '!') || (singleChar == '*')) {
                    error("In URLs, "+s+" can only be used to declare parameters after the ? symbol");
                } else {
                    this.pos = symbolStart; // go back before looked ahead symbol
                    url.add(checkAllowedInURL(this.pos, parseOneValue(-1)), Context.URL);
                }
            }
            if(inFragment) {
                // Parse rest of arguments as a simple list of nodes
                while(true) {
                    Node fragmentNode = parseOneValue(endOfListCharacter);
                    if(fragmentNode == null) {
                        error("Unexpected end of template after # URL fragment");
                    } if(fragmentNode == END_OF_LIST) {
                        break;
                    } else {
                        url.addFragmentNode(fragmentNode);
                    }
                }
                break;
            }
        }
        this.context = oldContext;
        popNestingAndCheckNodeWas(url, urlStart);
        return url;
    }

    protected Node checkAllowedInURL(int startPos, Node node) throws ParseException {
        if(!(node.allowedInURLContext())) {
            error("Not allowed in URL", startPos + 2);
        }
        return node;
    }

    // ----------------------------------------------------------------------

    // Views need to be rememebered during rendering so mechanisms like the
    // enclosing view can recall them. To make it as close to zero cost as
    // possible when the feature isn't used, only store views that are known
    // to be needed later when rendering. The parser keeps track of indicies
    // and tells the Driver, via the Template, how many are needed.
    protected int allocateRememberIndex() {
        return this.nextRememberIndex++;
    }

    // ----------------------------------------------------------------------

    protected int read() {
        if(pos >= this.source.length()) {
            return -1;
        }
        return source.charAt(this.pos++);
    }

    protected void move(int by) {
        this.pos += by;
        if(this.pos < 0 || this.pos >= this.source.length()) {
            throw new RuntimeException("move() out of bounds "+by+" to "+this.pos);
        }
    }

    protected boolean isWhitespace(int c) {
        return (c == ' ') || (c == '\n') || (c == '\r');
    }

    protected boolean isSingleCharSymbol(int c) {
        return (c == '(') || (c == ')') ||
                (c == '{') || (c == '}') ||
                (c == '<') || (c == '>') ||
                (c == '[') || (c == ']') ||
                (c == '?') || (c == '!') || (c == '*') || (c == '#') || // for special URL syntax
                (c == '/') ||
                (c == '"') ||
                (c == '=');
    }

    protected boolean isReservedCharacter(int c) {
        // NOTE: Don't remove ' from the reserved characters as the escaper assumes it can't be used
        return (c == ',') || (c == '\'') || (c == ';');
    }

    protected void popNestingAndCheckNodeWas(Node node, int errorPosition) throws ParseException {
        if(this.nesting.empty() || (this.nesting.pop() != node)) {
            error("Improperly nested block, check tags are balanced", errorPosition);
        }
    }

    protected void error(String error) throws ParseException {
        // Default error position is the last character consumed
        error(error, this.pos);
    }

    protected void error(String error, int errorPosition) throws ParseException {
        int p = errorPosition - 1;  // back one so the relevant character is found
        while((p > 0) && (this.source.charAt(p) == '\n')) {
            // If the position is on a newline, then move back another character,
            // errors at char 0 were actually at the end of the previous line
            --p;
        }
        int charPos = 0;
        for(; p >= 0; p--) {
            if(this.source.charAt(p) == '\n') {
                break;
            }
            charPos++;
        }
        int line = 1;
        for(; p >= 0; p--) {
            if(this.source.charAt(p) == '\n') {
                line++;
            }
        }
        throw new ParseException("Error at line "+line+" character "+charPos+": "+error);
    }

    protected CharSequence symbol() throws ParseException {
        // Skip whitespace
        int c = -1;
        do {
            if((c = read()) == -1) { return null; }
        } while(isWhitespace(c));
        move(-1);
        c = read();
        // Skip comments: // until end of line
        // Since isSingleCharSymbol() returns true for this character, it must be checked
        // separately, and / chars can only be found at this point in this function.
        if(c == '/') {
            int symbolEnd = this.pos - 1;
            if(read() != '/') {
                this.pos = symbolEnd + 1;   // not actually a comment
            } else {
                while(((c = read()) != -1) && (c != '\n')) { /* empty */ }
                return symbol();
            }
        }
        // Single character symbol? (never EOF at this point)
        if(isSingleCharSymbol(c)) {
            return this.source.subSequence(this.pos - 1, this.pos);
        }
        move(-1);
        // Multi-char symbol
        int startPos = this.pos;
        while(true) {
            c = read();
            if(c == -1) {
                return this.source.subSequence(startPos, this.pos);
            } else if(isWhitespace(c)) {
                return this.source.subSequence(startPos, this.pos - 1);
            } else if(isSingleCharSymbol(c)) {
                move(-1);
                return this.source.subSequence(startPos, this.pos);
            } else if(isReservedCharacter(c)) {
                error("Reserved character: \""+((char)c)+"\"");
            } else if(c == '\t') {
                error("Tab character in source, indent with 4 spaces");
            }
        }
    }

    protected String quotedString() throws ParseException {
        int c, startPos = this.pos;
        StringBuilder builder = new StringBuilder();
        while(true) {
            c = read();
            if(c == -1) {
                break;
            } else if(c == '\\') {
                int n = read();
                if(n == -1) { break; }
                switch(n) {
                    case 'n': builder.append('\n'); break;
                    case '"': case '\\': builder.append((char)n); break;
                    default: error("Unsupported escape code in quoted string: \\"+(char)n);
                }
            } else if((c == '<') || (c == '>')) {
                error("Angle brackets are not allowed in quoted strings. Use proper tags for security.");
            } else if(c == '"') {
                return builder.toString();
            } else {
                builder.append((char)c);
            }
        }
        // Report error message from the beginning of the string so it's useful
        error((c == -1) ?
            "Unexpected end of template in quoted string" :
            "Unexpected end of template in quoted character", startPos);
        return null;
    }

    protected boolean symbolIsSingleChar(CharSequence s, char ch) {
        return (s != null) && (s.length() == 1) && (s.charAt(0) == ch);
    }
}