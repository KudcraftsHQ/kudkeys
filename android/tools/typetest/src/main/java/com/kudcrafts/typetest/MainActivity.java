package com.kudcrafts.typetest;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;

/** One plain multi-line field (sentence caps + autocorrect allowed). Every change is logged as "TT|<ms>|<text>". */
public class MainActivity extends Activity {
    static MainActivity current;
    EditText field;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        current = this;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        field = new EditText(this);
        field.setInputType(inputType(getIntent().getStringExtra("type")));
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setTextSize(22);
        field.setMinLines(3);
        field.setHint("Type here");
        root.addView(field, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        field.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            public void onTextChanged(CharSequence s, int a, int c, int d) {}
            public void afterTextChanged(Editable s) {
                Log.i("TT", "TT|" + SystemClock.uptimeMillis() + "|" + s.toString().replace("\n", "\\n"));
            }
        });
        field.requestFocus();
    }

    /** `am start ... --es type <name>` picks the field's input type (layouts mode of swiftkey-compare). */
    static int inputType(String t) {
        if (t == null) t = "text";
        switch (t) {
            case "number": return InputType.TYPE_CLASS_NUMBER;
            case "number_signed": return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED;
            case "number_decimal": return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
            case "number_password": return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD;
            case "phone": return InputType.TYPE_CLASS_PHONE;
            case "datetime": return InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_NORMAL;
            case "date": return InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE;
            case "time": return InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME;
            default: return InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT;
        }
    }

    void clear() {
        field.setText("");
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.restartInput(field);
        imm.showSoftInput(field, 0);
        Log.i("TT", "TT|" + SystemClock.uptimeMillis() + "|<cleared>");
    }
}
