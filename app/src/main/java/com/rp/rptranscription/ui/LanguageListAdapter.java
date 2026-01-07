package com.rp.rptranscription.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.rp.rptranscription.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

class LanguageListAdapter extends RecyclerView.Adapter<LanguageListAdapter.VH> {

    interface OnLanguageClickListener {
        void onClick(String languageCode);
    }

    private final List<LanguageSelectionDialog.LanguageItem> all;
    private final List<LanguageSelectionDialog.LanguageItem> filtered;
    private final OnLanguageClickListener listener;
    private final String selectedCode;

    LanguageListAdapter(List<LanguageSelectionDialog.LanguageItem> items,
                        String selectedCode,
                        OnLanguageClickListener listener) {
        this.all = items == null ? new ArrayList<>() : new ArrayList<>(items);
        this.filtered = new ArrayList<>(this.all);
        this.listener = listener;
        this.selectedCode = selectedCode;
    }

    void filter(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        filtered.clear();
        if (q.isEmpty()) {
            filtered.addAll(all);
        } else {
            for (LanguageSelectionDialog.LanguageItem item : all) {
                if (item == null) {
                    continue;
                }
                String name = item.name == null ? "" : item.name.toLowerCase(Locale.ROOT);
                String code = item.code == null ? "" : item.code.toLowerCase(Locale.ROOT);
                if (name.contains(q) || code.contains(q)) {
                    filtered.add(item);
                }
            }
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.component_row_language, parent, false);
        return new VH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        LanguageSelectionDialog.LanguageItem item = filtered.get(position);
        holder.name.setText(item.name);
        holder.code.setText(item.code);

        boolean isSelected = selectedCode != null && selectedCode.equalsIgnoreCase(item.code);
        holder.itemView.setAlpha(isSelected ? 1.0f : 0.85f);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onClick(item.code);
            }
        });
    }

    @Override
    public int getItemCount() {
        return filtered.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView code;

        VH(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.language_name);
            code = itemView.findViewById(R.id.language_code);
        }
    }
}
