import { useToastStore } from '../../hooks/useToast';
import { CheckCircle, XCircle, AlertCircle, Info, X } from 'lucide-react';
import { cn } from '../../lib/utils';

export function ToastContainer() {
    const toasts = useToastStore((state) => state.toasts);
    const removeToast = useToastStore((state) => state.removeToast);

    return (
        <div className="fixed bottom-4 right-4 z-[100] flex flex-col gap-2 w-full max-w-sm pointer-events-none">
            {toasts.map((toast) => (
                <div
                    key={toast.id}
                    className={cn(
                        "pointer-events-auto flex items-center justify-between p-4 rounded-lg shadow-lg border animate-in fade-in slide-in-from-right-5 duration-300",
                        toast.type === 'success' && "bg-green-50 border-green-200 text-green-800 dark:bg-green-900/30 dark:border-green-800 dark:text-green-300",
                        toast.type === 'error' && "bg-red-50 border-red-200 text-red-800 dark:bg-red-900/30 dark:border-red-800 dark:text-red-300",
                        toast.type === 'warning' && "bg-yellow-50 border-yellow-200 text-yellow-800 dark:bg-yellow-900/30 dark:border-yellow-800 dark:text-yellow-300",
                        toast.type === 'info' && "bg-blue-50 border-blue-200 text-blue-800 dark:bg-blue-900/30 dark:border-blue-800 dark:text-blue-300"
                    )}
                >
                    <div className="flex items-center gap-3">
                        {toast.type === 'success' && <CheckCircle className="h-5 w-5 text-green-500" />}
                        {toast.type === 'error' && <XCircle className="h-5 w-5 text-red-500" />}
                        {toast.type === 'warning' && <AlertCircle className="h-5 w-5 text-yellow-500" />}
                        {toast.type === 'info' && <Info className="h-5 w-5 text-blue-500" />}
                        <p className="text-sm font-medium">{toast.message}</p>
                    </div>
                    <button
                        onClick={() => removeToast(toast.id)}
                        className="p-1 rounded-full hover:bg-black/5 dark:hover:bg-white/10 transition-colors"
                    >
                        <X className="h-4 w-4 opacity-50" />
                    </button>
                </div>
            ))}
        </div>
    );
}
