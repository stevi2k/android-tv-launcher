package com.kulwant.atvhome;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * One text field, on the TV, with the on-screen keyboard. Used by the
 * TV wizard wherever the phone wizard used to be the only place to
 * type: user names and weather cities. The caller starts this with
 * startActivityForResult and gets the typed text back in onActivityResult,
 * or RESULT_CANCELED if the user backs out. Standard Android focus is
 * used here (not the wizard's manual D-pad list), so the keyboard owns
 * the keys while it is open, and Back first closes the keyboard.
 */
public class TextEntryActivity extends Activity {

    private EditText edit;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        String title = in.getStringExtra("title");
        String hint = in.getStringExtra("hint");
        String initial = in.getStringExtra("initial");
        boolean capWords = in.getBooleanExtra("capWords", false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        int pad = Ui.dp(this, 24);
        root.setPadding(pad, pad, pad, pad);

        TextView tv = Ui.text(this, 700, 32, Color.WHITE);
        tv.setSingleLine(false);
        tv.setText(title == null ? "Type something" : title);
        root.addView(tv);

        if (hint != null && !hint.isEmpty()) {
            TextView sub = Ui.text(this, 400, 17, 0xCCFFFFFF);
            sub.setSingleLine(false);
            sub.setText(hint);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Ui.dp(this, 10);
            root.addView(sub, lp);
        }

        edit = new EditText(this);
        edit.setText(initial == null ? "" : initial);
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | (capWords ? InputType.TYPE_TEXT_FLAG_CAP_WORDS : 0));
        edit.setImeOptions(EditorInfo.IME_ACTION_DONE);
        edit.setTypeface(Ui.font(this, 500));
        edit.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20 * Ui.scale(this));
        edit.setTextColor(Color.WHITE);
        edit.setHintTextColor(0x99FFFFFF);
        edit.setBackground(Ui.rounded(0xFF1C1C1E, Ui.dp(this, 18), Ui.dp(this, 1), 0x33FFFFFF));
        edit.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 16));
        edit.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                save();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, -2);
        elp.topMargin = Ui.dp(this, 24);
        root.addView(edit, elp);

        Button save = new Button(this);
        save.setText("Save");
        save.setTextSize(TypedValue.COMPLEX_UNIT_PX, 19 * Ui.scale(this));
        save.setOnClickListener(v -> save());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = Ui.dp(this, 20);
        root.addView(save, blp);

        Button cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_PX, 19 * Ui.scale(this));
        cancel.setOnClickListener(v -> { setResult(RESULT_CANCELED); finish(); });
        root.addView(cancel, blp);

        TextView hint2 = Ui.text(this, 400, 15, 0x99FFFFFF);
        hint2.setSingleLine(false);
        hint2.setText("Type with the on-screen keyboard. Back closes the keyboard, then Back again cancels.");
        hint2.setPadding(0, Ui.dp(this, 12), 0, 0);
        root.addView(hint2);

        root.setFocusable(true);
        setContentView(root);
        edit.post(() -> {
            edit.requestFocus();
            try {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT);
            } catch (Exception ignored) {
            }
        });
    }

    private void save() {
        Intent out = new Intent();
        out.putExtra("text", edit.getText().toString());
        setResult(RESULT_OK, out);
        finish();
    }
}
