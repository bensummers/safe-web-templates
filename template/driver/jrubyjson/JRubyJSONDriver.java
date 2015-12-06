package template.driver.jrubyjson;

import java.util.Map;
import java.util.Iterator;

import template.Driver;
import template.Template;
import template.Context;

import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.RubyObject;
import org.jruby.RubyString;
import org.jruby.RubyBoolean;
import org.jruby.RubyArray;

class JRubyJSONDriver extends Driver {
    private IRubyObject rootView;
    private Map<String,Template> inclusions;

    public JRubyJSONDriver(IRubyObject view, Map<String,Template> inclusions) {
        this.rootView = view;
        this.inclusions = inclusions;
    }

    public Driver driverWithNewRoot(Object rootView) {
        if(!(rootView instanceof IRubyObject)) {
            throw new RuntimeException("Unexpected view object when creating driver for new root");
        }
        return new JRubyJSONDriver((IRubyObject)rootView, this.inclusions);
    }

    public Object getRootView() {
        return this.rootView;
    }

    public Object getValueFromView(Object view, String[] path) {
        if(view instanceof IRubyObject) {
            IRubyObject o = (IRubyObject)view;
            for(String key : path) {
                if(o == null || o.isNil()) { return null; }
                o = o.callMethod(o.getRuntime().getCurrentContext(), "[]",
                        RubyString.newUnicodeString(o.getRuntime(), key));
            }
            return ((o == null) || o.isNil()) ? null : o;
        } else if(path.length == 0) {
            return view;    // to allow . value to work
        }
        return null;
    }

    public String valueToStringRepresentation(Object value) {
        if(value instanceof IRubyObject) {
            IRubyObject o = (IRubyObject)value;;
            if(o.isNil()) { return null; }
            IRubyObject str = o.callMethod(o.getRuntime().getCurrentContext(), "to_s");
            if(!(str instanceof RubyString)) { return null; }
            return ((RubyString)str).decodeString();
        }
        return (value == null) ? null : value.toString();
    }

    @SuppressWarnings("unchecked")
    public Iterable<Object> valueToIterableViewList(Object value) {
        if(value instanceof RubyArray) {
            return (Iterable<Object>) () -> ((RubyArray)value).iterator();
        }
        return null;
    }

    public boolean valueIsTruthy(Object value) {
        if(value instanceof IRubyObject) {
            IRubyObject o = (IRubyObject)value;
            if(o == null) {
                return false;
            } else if(o instanceof RubyString) {
                return !(((RubyString)o).isEmpty());
            } else if(o instanceof RubyBoolean) {
                return (o instanceof RubyBoolean.True);
            } else if(o instanceof RubyArray) {
                return !(((RubyArray)o).isEmpty());
            }
            return false;
        } else {
            return super.valueIsTruthy(value);
        }
    }

    public void renderInclusion(String inclusionName, StringBuilder builder, Object view, Context context) {
        // TODO: Error if inclusion not found?
        if(this.inclusions == null) { return; }
        Template template = this.inclusions.get(inclusionName);
        if(template != null) {
            template.renderAsInclusion(builder, this, view, context);
        }
    }
}