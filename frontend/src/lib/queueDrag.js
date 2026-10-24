import { useState } from "react";

/* The drag handles of the build and recruit queues (the original's jQuery UI sortable): drag a waiting order by its
   handle onto another waiting row and it takes that row's place. onMove(from, to) gets queue indexes; canDrop(from, to)
   can refuse a row (the combined recruit list holds several buildings' queues, and an order only moves within its own). */
export function useQueueDrag(onMove, canDrop = () => true) {
  const [from, setFrom] = useState(null);
  const [over, setOver] = useState(null);
  const end = () => {
    setFrom(null);
    setOver(null);
  };
  const handle = (index) => ({
    draggable: true,
    onDragStart: (e) => {
      e.dataTransfer.effectAllowed = "move";
      e.dataTransfer.setData("text/plain", String(index)); // Firefox only drags with data set
      setFrom(index);
    },
    onDragEnd: end,
  });
  // the row the order would land on gets a dashed red line on the side it goes in
  const row = (index) => {
    if (!onMove) return {};
    const target = from !== null && over === index && from !== index && canDrop(from, index);
    return {
      onDragOver: (e) => {
        if (from === null || !canDrop(from, index)) return;
        e.preventDefault();
        setOver(index);
      },
      onDrop: (e) => {
        e.preventDefault();
        if (from !== null && from !== index && canDrop(from, index)) onMove(from, index);
        end();
      },
      style: target ? { [from < index ? "borderBottom" : "borderTop"]: "2px dashed #b40000" } : undefined,
    };
  };
  return { handle, row };
}
