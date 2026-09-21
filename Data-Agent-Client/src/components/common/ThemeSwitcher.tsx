import { useTheme } from "../../hooks/useTheme";
import { Button } from "../ui/Button";
import { Moon, Sun } from "lucide-react";

export function ThemeSwitcher() {
    const { theme, toggleTheme } = useTheme();

    return (
        <Button
            variant="glass"
            size="icon"
            onClick={toggleTheme}
            className="rounded-full transition-transform hover:scale-110"
            title={`Switch to ${theme === 'light' ? 'dark' : 'light'} mode`}
        >
            {theme === "light" ? (
                <Moon className="h-5 w-5" />
            ) : (
                <Sun className="h-5 w-5" />
            )}
        </Button>
    );
}
