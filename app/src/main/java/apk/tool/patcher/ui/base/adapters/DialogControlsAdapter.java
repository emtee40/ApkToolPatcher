package apk.tool.patcher.ui.base.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import apk.tool.patcher.R;
import ru.svolf.melissa.model.ControlsItem;

public class DialogControlsAdapter extends RecyclerView.Adapter<DialogControlsAdapter.ViewHolder> {
    List<ControlsItem> items;
    private DialogControlsAdapter.OnItemClickListener itemClickListener;

    public DialogControlsAdapter(List<ControlsItem> items) {
        this.items = items;
    }

    public void setItemClickListener(DialogControlsAdapter.OnItemClickListener itemClickListener) {
        this.itemClickListener = itemClickListener;
    }

    public ControlsItem getItem(int position) {
        return items.get(position);
    }

    @NonNull
    @Override
    public DialogControlsAdapter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_dialog_controls, parent, false);
        return new DialogControlsAdapter.ViewHolder(v);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public void onBindViewHolder(DialogControlsAdapter.ViewHolder holder, int position) {
        ControlsItem item = getItem(position);
        assert item != null;

        holder.text.setText(item.getTitle());
        holder.icon.setImageResource(item.getIcon());
    }

    public interface OnItemClickListener {
        void onItemClick(ControlsItem menuItem, int position);
    }

    public class ViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener {
        public TextView text;
        public ImageView icon;

        public ViewHolder(View v) {
            super(v);
            text = v.findViewById(R.id.list_item_content);
            icon = v.findViewById(R.id.list_item_icon);

            v.setOnClickListener(this);
        }

        @Override
        public void onClick(View view) {
            if (itemClickListener != null) {
                itemClickListener.onItemClick(getItem(getLayoutPosition()), getLayoutPosition());
            }
        }
    }
}
