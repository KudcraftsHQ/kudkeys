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
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
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

    void clear() {
        field.setText("");
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.restartInput(field);
        imm.showSoftInput(field, 0);
        Log.i("TT", "TT|" + SystemClock.uptimeMillis() + "|<cleared>");
    }
}
